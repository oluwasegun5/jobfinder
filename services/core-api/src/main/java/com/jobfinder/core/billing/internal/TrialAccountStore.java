package com.jobfinder.core.billing.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code trial_accounts}: one row per user on the trial path (or LEGACY: no trial). No row means the user has not
 * reached M1. Deleted with the account (FK cascade, and explicitly by the deletion handler).
 */
@Component
class TrialAccountStore {

    enum State {
        GRANTED, HOLD, DENIED, LEGACY
    }

    record TrialAccount(UUID userId, State state, int milestones, int riskScore, Instant startedAt, Instant expiresAt,
            Instant endedAt) {

        /** Whether credits from this trial are live at {@code now}. */
        boolean running(Instant now) {
            return (state == State.GRANTED || state == State.HOLD) && endedAt == null && expiresAt != null
                    && now.isBefore(expiresAt);
        }
    }

    private final JdbcClient jdbc;

    TrialAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static TrialAccount map(ResultSet rs, int n) throws SQLException {
        return new TrialAccount(rs.getObject("user_id", UUID.class), State.valueOf(rs.getString("state")),
                rs.getInt("milestones"), rs.getInt("risk_score"), instant(rs, "started_at"),
                instant(rs, "expires_at"), instant(rs, "ended_at"));
    }

    Optional<TrialAccount> find(UUID userId) {
        return jdbc.sql("select * from trial_accounts where user_id = :user").param("user", userId)
                .query(TrialAccountStore::map).optional();
    }

    /** Inserts or replaces the user's row. */
    void save(TrialAccount a, Instant now) {
        jdbc.sql("""
                insert into trial_accounts (user_id, state, milestones, risk_score, started_at, expires_at, ended_at,
                                            created_at, updated_at)
                values (:user, :state, :milestones, :score, :started, :expires, :ended, :now, :now)
                on conflict (user_id) do update
                   set state = excluded.state, milestones = excluded.milestones, risk_score = excluded.risk_score,
                       started_at = excluded.started_at, expires_at = excluded.expires_at,
                       ended_at = excluded.ended_at, updated_at = excluded.updated_at
                """)
                .param("user", a.userId()).param("state", a.state().name()).param("milestones", a.milestones())
                .param("score", a.riskScore())
                .param("started", at(a.startedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expires", at(a.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("ended", at(a.endedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", at(now)).update();
    }

    /** Running trials whose time is up, oldest first. */
    List<UUID> due(Instant now, int limit) {
        return jdbc.sql("""
                select user_id from trial_accounts
                 where state in ('GRANTED', 'HOLD') and ended_at is null and expires_at <= :now
                 order by expires_at limit :limit
                """).param("now", at(now)).param("limit", limit).query(UUID.class).list();
    }

    void delete(UUID userId) {
        jdbc.sql("delete from trial_accounts where user_id = :user").param("user", userId).update();
    }
}
