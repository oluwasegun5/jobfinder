package com.jobfinder.core.billing.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.billing.internal.PaymentProvider.PaymentProviderException;

/**
 * Stopping a subscription at its provider when we no longer have the user (an account deletion) or no longer want to
 * serve it (it lapsed). Best effort: a failure never blocks what asked for it; it is written to
 * {@code remote_cancellations} (provider ids only, no user) and retried with a growing delay until it works.
 */
@Component
class RemoteCancellations {

    static final int MAX_ATTEMPTS = 12;

    private static final Logger log = LoggerFactory.getLogger(RemoteCancellations.class);

    private final Map<Provider, PaymentProvider> providers = new EnumMap<>(Provider.class);
    private final JdbcClient jdbc;

    RemoteCancellations(List<PaymentProvider> providers, JdbcClient jdbc) {
        providers.forEach(p -> this.providers.put(p.provider(), p));
        this.jdbc = jdbc;
    }

    /** Tries to end the subscription now; on failure remembers it for a retry. Never throws. */
    void cancelOrQueue(Provider provider, String ref, Instant now) {
        try {
            providers.get(provider).cancelNow(ref);
        } catch (RuntimeException e) {
            log.warn("Remote cancellation failed, queued for retry: provider={} cause={}", provider.slug(),
                    e.getMessage());
            jdbc.sql("""
                    insert into remote_cancellations (provider, provider_ref, attempts, next_attempt_at, created_at)
                    values (:p, :r, 1, :next, :now)
                    on conflict (provider, provider_ref) do nothing
                    """).param("p", provider.name()).param("r", ref)
                    .param("next", at(now.plus(delay(1)))).param("now", at(now)).update();
        }
    }

    /** Retries the queued cancellations that are due. @return how many were cleared */
    int retryDue(Instant now, int limit) {
        record Row(long id, String provider, String ref, int attempts) {
        }
        List<Row> due = jdbc.sql("""
                select id, provider, provider_ref, attempts from remote_cancellations
                 where next_attempt_at <= :now and attempts < :max order by next_attempt_at limit :limit
                """).param("now", at(now)).param("max", MAX_ATTEMPTS).param("limit", limit)
                .query((rs, row) -> new Row(rs.getLong("id"), rs.getString("provider"), rs.getString("provider_ref"),
                        rs.getInt("attempts")))
                .list();
        int cleared = 0;
        for (Row row : due) {
            try {
                providers.get(Provider.valueOf(row.provider())).cancelNow(row.ref());
                jdbc.sql("delete from remote_cancellations where id = :id").param("id", row.id()).update();
                cleared++;
            } catch (PaymentProviderException e) {
                int attempts = row.attempts() + 1;
                if (attempts >= MAX_ATTEMPTS) {
                    log.error("Remote cancellation gave up after {} attempts: provider={} ref={}", attempts,
                            row.provider(), row.ref());
                }
                jdbc.sql("update remote_cancellations set attempts = :a, next_attempt_at = :next where id = :id")
                        .param("a", attempts).param("next", at(now.plus(delay(attempts)))).param("id", row.id())
                        .update();
            }
        }
        return cleared;
    }

    private static Duration delay(int attempts) {
        return Duration.ofMinutes(10L << Math.min(attempts, 8));
    }

    private static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
