package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.billing.internal.CreditLedgerStore.Position;
import com.jobfinder.core.billing.internal.PlanCatalog.Plan;

/**
 * Every credit that enters a ledger: the Free plan's monthly grant, a paid plan's grant for a billing period, and
 * top-ups. Each is written under an idempotency key, so a repeated job run, a duplicate or replayed webhook and two
 * racing lazy grants all write it once (the ledger store takes the user's lock, then the unique key decides).
 *
 * <p><b>Rollover.</b> Plan credits that are still unspent when the next period's grant arrives expire, except up to
 * {@code app.billing.rollover-cap-credits} (default 0: none). The expiry is its own ledger line
 * ({@code PLAN_EXPIRY}) written just before the grant in the same transaction, so the history shows both. Top-up
 * credits are never expired. A negative balance (a call that overshot) is simply added to: the grant pays it off.
 */
@Service
class CreditGrants {

    private static final Logger log = LoggerFactory.getLogger(CreditGrants.class);

    private final CreditLedgerStore ledger;
    private final SubscriptionStore subscriptions;
    private final PlanCatalog plans;
    private final BillingProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;

    CreditGrants(CreditLedgerStore ledger, SubscriptionStore subscriptions, PlanCatalog plans,
            BillingProperties properties, JdbcClient jdbc, Clock clock) {
        this.ledger = ledger;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The calendar month (UTC) a free grant is for, for example {@code 2026-10}. */
    static String period(Instant at) {
        return YearMonth.from(at.atZone(ZoneOffset.UTC)).toString();
    }

    static String freeKey(UUID userId, String period) {
        return "free:" + userId + ":" + period;
    }

    static String planKey(UUID subscriptionId, Instant periodEnd) {
        return "plan:" + subscriptionId + ":" + periodEnd.getEpochSecond();
    }

    static String topupKey(Provider provider, String paymentRef) {
        return "topup:" + provider.slug() + ":" + paymentRef;
    }

    /**
     * Grants the Free plan's credits for the calendar month of {@code now}, once per user per month. Nothing happens
     * for a user on a live paid subscription (the paid plan grants for them), or when the month was already granted.
     * Safe to call at any time and from any number of threads: this is what the monthly job and the gate's lazy grant
     * both use.
     *
     * @return true when this call wrote the grant
     */
    @Transactional
    public boolean grantFreeIfDue(UUID userId, Instant now) {
        String key = freeKey(userId, period(now));
        if (ledger.keyExists(key) || subscriptions.live(userId).isPresent()) {
            return false;
        }
        ledger.lock(userId);
        if (ledger.keyExists(key)) {
            return false;
        }
        return grant(userId, plans.free().monthlyCredits(), key, true, now);
    }

    /** As {@link #grantFreeIfDue(UUID, Instant)} at the clock's time. */
    @Transactional
    public boolean grantFreeIfDue(UUID userId) {
        return grantFreeIfDue(userId, Instant.now(clock));
    }

    /**
     * Grants a paid plan's credits for one billing period, once (the key is the subscription and the period's end).
     *
     * @param expireUnused whether unspent plan credits expire first (a renewal does; the first payment does not, so
     *                     upgrading never costs a user the free credits they had left)
     * @return true when this call wrote the grant
     */
    @Transactional
    boolean grantPlanPeriod(UUID userId, Plan plan, UUID subscriptionId, Instant periodEnd, boolean expireUnused,
            Instant now) {
        ledger.lock(userId);
        String key = planKey(subscriptionId, periodEnd);
        if (ledger.keyExists(key)) {
            return false;
        }
        return grant(userId, plan.monthlyCredits(), key, expireUnused, now);
    }

    /** Credits a purchased pack once; true when this call wrote it. TOPUP credits do not expire. */
    @Transactional
    boolean topup(UUID userId, BigDecimal credits, Provider provider, String paymentRef, Instant now) {
        return ledger.append(userId, credits, LedgerReason.TOPUP, null, topupKey(provider, paymentRef), now);
    }

    private boolean grant(UUID userId, BigDecimal credits, String key, boolean expireUnused, Instant now) {
        if (credits.signum() <= 0) {
            return false;
        }
        if (expireUnused) {
            Position position = ledger.position(userId);
            BigDecimal expiring = position.planPortion().subtract(properties.rolloverCapCredits());
            if (expiring.signum() > 0) {
                ledger.append(userId, expiring.negate(), LedgerReason.PLAN_EXPIRY, null, "expiry:" + key, now);
            }
        }
        boolean written = ledger.append(userId, credits, LedgerReason.PLAN_GRANT, null, key, now);
        if (written) {
            log.info("Granted credits: user={} key={}", userId, key);
        }
        return written;
    }

    /**
     * Users who should have this month's free grant and do not: active, verified, not deleted, not on a paid plan.
     * Used by the monthly job; {@link #grantFreeIfDue} stays the single place that decides and writes.
     */
    java.util.List<UUID> usersMissingFreeGrant(String period, UUID after, int limit) {
        return jdbc.sql("""
                select u.id from users u
                 where u.status = 'ACTIVE' and u.deleted_at is null and u.email_verified_at is not null
                   and (cast(:after as uuid) is null or u.id > cast(:after as uuid))
                   and not exists (select 1 from credit_ledger l where l.idempotency_key = 'free:' || u.id || ':' || :period)
                   and not exists (select 1 from subscriptions s where s.user_id = u.id
                                    and s.status in ('ACTIVE', 'PAST_DUE'))
                 order by u.id limit :limit
                """)
                .param("period", period).param("limit", limit)
                .param("after", after == null ? null : after.toString(), java.sql.Types.VARCHAR)
                .query(UUID.class).list();
    }
}
