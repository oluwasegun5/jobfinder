package com.jobfinder.core.billing.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The only writer of {@code subscriptions}. */
@Component
class SubscriptionStore {

    enum Status { PENDING, ACTIVE, PAST_DUE, CANCELED }

    record Subscription(UUID id, UUID userId, UUID planId, Provider provider, String providerRef,
            String providerCustomer, Status status, Instant currentPeriodEnd, boolean cancelAtPeriodEnd,
            Instant pastDueSince, Instant lastEventAt, Instant createdAt, Instant updatedAt) {

        boolean live() {
            return status == Status.ACTIVE || status == Status.PAST_DUE;
        }
    }

    private static final String COLUMNS = """
            id, user_id, plan_id, provider, provider_ref, provider_customer, status, current_period_end,
            cancel_at_period_end, past_due_since, last_event_at, created_at, updated_at""";

    private static final RowMapper<Subscription> MAPPER = (rs, row) -> new Subscription(
            rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("plan_id", UUID.class),
            Provider.valueOf(rs.getString("provider")), rs.getString("provider_ref"),
            rs.getString("provider_customer"), Status.valueOf(rs.getString("status")),
            instant(rs.getTimestamp("current_period_end")), rs.getBoolean("cancel_at_period_end"),
            instant(rs.getTimestamp("past_due_since")), instant(rs.getTimestamp("last_event_at")),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));

    private final JdbcClient jdbc;

    SubscriptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime odt(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    Optional<Subscription> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from subscriptions where id = :id").param("id", id).query(MAPPER)
                .optional();
    }

    /** The user's ACTIVE or PAST_DUE subscription, if any (there is at most one). */
    Optional<Subscription> live(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from subscriptions where user_id = :u and status in "
                + "('ACTIVE', 'PAST_DUE')").param("u", userId).query(MAPPER).optional();
    }

    /** The user's newest subscription row of any status (what the billing page describes when none is live). */
    Optional<Subscription> latest(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from subscriptions where user_id = :u order by created_at desc, id "
                + "limit 1").param("u", userId).query(MAPPER).optional();
    }

    Optional<Subscription> byRef(Provider provider, String ref) {
        return jdbc.sql("select " + COLUMNS + " from subscriptions where provider = :p and provider_ref = :r")
                .param("p", provider.name()).param("r", ref).query(MAPPER).optional();
    }

    /** A live subscription of this provider's customer (the way a Paystack renewal finds its user). */
    Optional<Subscription> liveByCustomer(Provider provider, String customer) {
        return jdbc.sql("select " + COLUMNS + " from subscriptions where provider = :p and provider_customer = :c "
                + "and status in ('ACTIVE', 'PAST_DUE') order by created_at desc limit 1")
                .param("p", provider.name()).param("c", customer).query(MAPPER).optional();
    }

    Optional<Subscription> pending(UUID userId, Provider provider) {
        return jdbc.sql("select " + COLUMNS + " from subscriptions where user_id = :u and provider = :p and "
                + "status = 'PENDING' order by created_at desc limit 1")
                .param("u", userId).param("p", provider.name()).query(MAPPER).optional();
    }

    /** Starts a checkout: replaces any earlier unfinished one of the user's with a fresh PENDING row. */
    Subscription startPending(UUID userId, UUID planId, Provider provider, Instant now) {
        jdbc.sql("delete from subscriptions where user_id = :u and status = 'PENDING'").param("u", userId).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into subscriptions (id, user_id, plan_id, provider, status, cancel_at_period_end,
                                           last_event_at, created_at, updated_at)
                values (:id, :u, :plan, :p, 'PENDING', false, :epoch, :now, :now)
                """)
                .param("id", id).param("u", userId).param("plan", planId).param("p", provider.name())
                .param("epoch", odt(Instant.EPOCH)).param("now", odt(now)).update();
        return find(id).orElseThrow();
    }

    Subscription insertActive(UUID userId, UUID planId, Provider provider, String ref, String customer,
            Instant periodEnd, Instant eventAt, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into subscriptions (id, user_id, plan_id, provider, provider_ref, provider_customer, status,
                                           current_period_end, cancel_at_period_end, last_event_at, created_at,
                                           updated_at)
                values (:id, :u, :plan, :p, :ref, :cust, 'ACTIVE', :end, false, :ev, :now, :now)
                """)
                .param("id", id).param("u", userId).param("plan", planId).param("p", provider.name())
                .param("ref", ref).param("cust", customer).param("end", odt(periodEnd)).param("ev", odt(eventAt))
                .param("now", odt(now)).update();
        return find(id).orElseThrow();
    }

    void save(Subscription s, Instant now) {
        jdbc.sql("""
                update subscriptions set plan_id = :plan, provider_ref = :ref, provider_customer = :cust,
                       status = :status, current_period_end = :end, cancel_at_period_end = :cancel,
                       past_due_since = :since, last_event_at = :ev, updated_at = :now
                 where id = :id
                """)
                .param("id", s.id()).param("plan", s.planId()).param("ref", s.providerRef())
                .param("cust", s.providerCustomer()).param("status", s.status().name())
                .param("end", odt(s.currentPeriodEnd())).param("cancel", s.cancelAtPeriodEnd())
                .param("since", odt(s.pastDueSince())).param("ev", odt(s.lastEventAt())).param("now", odt(now))
                .update();
    }

    /** Live subscriptions whose period ended before {@code cutoff} or that are past due since before it. */
    List<Subscription> overdue(Instant now, java.time.Duration grace, int limit) {
        Instant cutoff = now.minus(grace);
        return jdbc.sql("select " + COLUMNS + """
                 from subscriptions
                 where (status = 'ACTIVE' and current_period_end < :now and (cancel_at_period_end
                            or current_period_end < :cutoff))
                    or (status = 'PAST_DUE' and (past_due_since < :cutoff
                            or (cancel_at_period_end and current_period_end < :now)))
                 order by current_period_end limit :limit
                """).param("now", odt(now)).param("cutoff", odt(cutoff)).param("limit", limit).query(MAPPER).list();
    }

    void deleteForUser(UUID userId) {
        jdbc.sql("delete from subscriptions where user_id = :u").param("u", userId).update();
    }

    /** The provider references of the user's subscriptions that may still bill (PENDING rows have none). */
    List<Subscription> remoteLive(UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from subscriptions where user_id = :u and provider_ref is not null "
                + "and status in ('ACTIVE', 'PAST_DUE')").param("u", userId).query(MAPPER).list();
    }
}
