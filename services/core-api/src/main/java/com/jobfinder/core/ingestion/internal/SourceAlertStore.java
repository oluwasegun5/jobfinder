package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads and writes {@code source_alerts}: which alert of which source is open, and when it was last announced. */
@Component
class SourceAlertStore {

    record AlertRow(UUID sourceId, AlertRule rule, boolean active, Instant firstFiredAt, Instant lastNotifiedAt,
            Instant resolvedAt, int occurrences, int notifications, String detail) {
    }

    private static final String SELECT = """
            select source_id, rule, active, first_fired_at, last_notified_at, resolved_at, occurrences, notifications,
                   detail
            from source_alerts
            """;

    private final JdbcClient jdbc;

    SourceAlertStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AlertRow> find(UUID sourceId, AlertRule rule) {
        return jdbc.sql(SELECT + " where source_id = :sourceId and rule = :rule")
                .param("sourceId", sourceId)
                .param("rule", rule.name())
                .query((rs, row) -> map(rs))
                .optional();
    }

    List<AlertRow> findActive() {
        return jdbc.sql(SELECT + " where active order by first_fired_at")
                .query((rs, row) -> map(rs))
                .list();
    }

    /** Starts a new episode, replacing the row of a resolved one. */
    void open(UUID sourceId, AlertRule rule, UUID runId, String detail, Instant now) {
        jdbc.sql("""
                insert into source_alerts (source_id, rule, active, first_fired_at, last_notified_at, resolved_at,
                                           occurrences, notifications, last_run_id, detail, created_at, updated_at)
                values (:sourceId, :rule, true, :now, null, null, 1, 0, :runId, :detail, :now, :now)
                on conflict (source_id, rule) do update
                   set active = true, first_fired_at = :now, last_notified_at = null, resolved_at = null,
                       occurrences = 1, notifications = 0, last_run_id = :runId, detail = :detail, updated_at = :now
                """)
                .param("sourceId", sourceId)
                .param("rule", rule.name())
                .param("runId", runId)
                .param("detail", detail)
                .param("now", utc(now))
                .update();
    }

    /** Another run in breach of an alert that is already open. */
    void touch(UUID sourceId, AlertRule rule, UUID runId, String detail, Instant now) {
        jdbc.sql("""
                update source_alerts set occurrences = occurrences + 1, last_run_id = :runId, detail = :detail,
                                         updated_at = :now
                 where source_id = :sourceId and rule = :rule
                """)
                .param("sourceId", sourceId)
                .param("rule", rule.name())
                .param("runId", runId)
                .param("detail", detail)
                .param("now", utc(now))
                .update();
    }

    void markNotified(UUID sourceId, AlertRule rule, Instant now) {
        jdbc.sql("""
                update source_alerts set last_notified_at = :now, notifications = notifications + 1, updated_at = :now
                 where source_id = :sourceId and rule = :rule
                """)
                .param("sourceId", sourceId)
                .param("rule", rule.name())
                .param("now", utc(now))
                .update();
    }

    /** @return whether an open alert was closed */
    boolean resolve(UUID sourceId, AlertRule rule, Instant now) {
        return jdbc.sql("""
                update source_alerts set active = false, resolved_at = :now, updated_at = :now
                 where source_id = :sourceId and rule = :rule and active
                """)
                .param("sourceId", sourceId)
                .param("rule", rule.name())
                .param("now", utc(now))
                .update() == 1;
    }

    private static AlertRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AlertRow(rs.getObject("source_id", UUID.class), AlertRule.valueOf(rs.getString("rule")),
                rs.getBoolean("active"), instant(rs, "first_fired_at"), instant(rs, "last_notified_at"),
                instant(rs, "resolved_at"), rs.getInt("occurrences"), rs.getInt("notifications"),
                rs.getString("detail"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
