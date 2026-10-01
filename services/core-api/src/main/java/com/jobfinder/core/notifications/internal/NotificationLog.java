package com.jobfinder.core.notifications.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The outbox and audit trail ({@code notification_log}, V24; docs/adr/0028-notifications.md). Claiming a row is the
 * idempotency guard: the unique key (user, kind, scope, window) lets exactly one caller win a window, so a digest or an
 * alert is built and sent once however many times, and from however many instances, the run is started.
 */
@Component
class NotificationLog {

    enum Kind {
        FOR_YOU_DIGEST, SAVED_SEARCH_DIGEST, INSTANT_MATCH_ALERT, INSTANT_SEARCH_ALERT;

        boolean isDigest() {
            return this == FOR_YOU_DIGEST || this == SAVED_SEARCH_DIGEST;
        }
    }

    /** The right to send one email: the row and which attempt this is (1 is the first). */
    record Claim(UUID id, int attempts) {
    }

    private final JdbcClient jdbc;

    NotificationLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the window for sending. Wins when nobody has claimed it yet, or when an earlier attempt failed and is due
     * again (fewer than {@code maxAttempts}, past its retry time), or when a claimant died without finishing
     * ({@code staleAfter}). Empty when the window is sent, skipped, being sent, out of attempts or not due yet.
     */
    Optional<Claim> claim(UUID userId, Kind kind, String scope, UUID savedSearchId, String windowKey, Instant now,
            int maxAttempts, Duration staleAfter) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                insert into notification_log (id, user_id, kind, scope, saved_search_id, window_key, status, attempts,
                       created_at, updated_at)
                values (:id, :user, :kind, :scope, :search, :window, 'PENDING', 1, :now, :now)
                on conflict (user_id, kind, scope, window_key) do nothing
                """)
                .param("id", id).param("user", userId).param("kind", kind.name()).param("scope", scope)
                .param("search", savedSearchId).param("window", windowKey).param("now", utc(now)).update();
        if (inserted == 1) {
            return Optional.of(new Claim(id, 1));
        }
        return jdbc.sql("""
                update notification_log set status = 'PENDING', attempts = attempts + 1, updated_at = :now
                 where user_id = :user and kind = :kind and scope = :scope and window_key = :window
                   and attempts < :max
                   and ((status = 'FAILED' and next_attempt_at <= :now)
                        or (status = 'PENDING' and updated_at < :stale))
                returning id, attempts
                """)
                .param("user", userId).param("kind", kind.name()).param("scope", scope).param("window", windowKey)
                .param("now", utc(now)).param("max", maxAttempts).param("stale", utc(now.minus(staleAfter)))
                .query((rs, row) -> new Claim(rs.getObject("id", UUID.class), rs.getInt("attempts"))).optional();
    }

    void sent(UUID id, List<UUID> jobIds, Instant now) {
        jdbc.sql("""
                update notification_log set status = 'SENT', job_ids = cast(:jobs as uuid[]), item_count = :count,
                       sent_at = :now, updated_at = :now, next_attempt_at = null, last_error = null
                 where id = :id
                """)
                .param("id", id).param("jobs", uuidArray(jobIds)).param("count", jobIds.size()).param("now", utc(now))
                .update();
    }

    /** Nothing to send in this window; the window is closed so it is not worked out again. */
    void skipped(UUID id, Instant now) {
        jdbc.sql("update notification_log set status = 'SKIPPED', updated_at = :now where id = :id")
                .param("id", id).param("now", utc(now)).update();
    }

    /** The attempt failed; {@code nextAttempt} is when it may be tried again. {@code error} is a class name only. */
    void failed(UUID id, String error, Instant now, Instant nextAttempt) {
        jdbc.sql("""
                update notification_log set status = 'FAILED', last_error = :error, updated_at = :now,
                       next_attempt_at = :next
                 where id = :id
                """)
                .param("id", id).param("error", truncate(error)).param("now", utc(now)).param("next", utc(nextAttempt))
                .update();
    }

    /** The jobs already listed in an email of one of these kinds sent since {@code since} (optionally of one scope). */
    Set<UUID> sentJobs(UUID userId, Collection<Kind> kinds, Instant since, String scope) {
        return new HashSet<>(jdbc.sql("""
                select distinct j from notification_log l, unnest(l.job_ids) as j
                 where l.user_id = :user and l.status = 'SENT' and l.kind in (:kinds) and l.sent_at >= :since
                   and (cast(:scope as text) is null or l.scope = :scope)
                """)
                .param("user", userId).param("kinds", kinds.stream().map(Kind::name).toList())
                .param("since", utc(since)).param("scope", scope).query(UUID.class).list());
    }

    /** Emails of these kinds sent to the user since {@code since} (the instant alerts' daily cap). */
    int sentCount(UUID userId, Collection<Kind> kinds, Instant since) {
        return jdbc.sql("""
                select count(*) from notification_log
                 where user_id = :user and status = 'SENT' and kind in (:kinds) and sent_at >= :since
                """)
                .param("user", userId).param("kinds", kinds.stream().map(Kind::name).toList())
                .param("since", utc(since)).query(Integer.class).single();
    }

    /** Deletes rows created before {@code before}; returns how many. */
    int prune(Instant before) {
        return jdbc.sql("delete from notification_log where created_at < :before and status <> 'PENDING'")
                .param("before", utc(before)).update();
    }

    private static String uuidArray(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String truncate(String value) {
        return value == null ? null : value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
