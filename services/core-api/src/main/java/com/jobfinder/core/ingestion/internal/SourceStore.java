package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.SourceKind;

/** Reads and updates {@code sources} and {@code source_targets}. */
@Component
class SourceStore {

    record SourceRow(UUID id, String code, SourceKind kind, boolean enabled, Instant lastRunAt, SourceHealth health,
            SourceSettings settings) {
    }

    record TargetRow(UUID id, String identifier, UUID companyId) {
    }

    private static final String SELECT = """
            select id, code, kind, enabled, last_run_at, health,
                   config ->> 'intervalMinutes'   as interval_minutes,
                   config ->> 'jitterSeconds'     as jitter_seconds,
                   config ->> 'requestsPerSecond' as requests_per_second,
                   config ->> 'retryMaxAttempts'  as retry_max_attempts
            from sources
            """;

    private final JdbcClient jdbc;
    private final IngestionProperties properties;
    private final RowMapper<SourceRow> sourceMapper;

    SourceStore(JdbcClient jdbc, IngestionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.sourceMapper = (rs, row) -> {
            OffsetDateTime lastRun = rs.getObject("last_run_at", OffsetDateTime.class);
            return new SourceRow(rs.getObject("id", UUID.class), rs.getString("code"),
                    SourceKind.valueOf(rs.getString("kind")), rs.getBoolean("enabled"),
                    lastRun == null ? null : lastRun.toInstant(), SourceHealth.valueOf(rs.getString("health")),
                    SourceSettings.resolve(properties.defaults(), rs.getString("interval_minutes"),
                            rs.getString("jitter_seconds"), rs.getString("requests_per_second"),
                            rs.getString("retry_max_attempts")));
        };
    }

    /** Adds the source if it is not there yet; an existing row (and any admin tuning on it) is left alone. */
    void register(String code, SourceKind kind) {
        jdbc.sql("""
                insert into sources (id, code, kind, created_at, updated_at)
                values (:id, :code, :kind, now(), now())
                on conflict (code) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("code", code)
                .param("kind", kind.name())
                .update();
    }

    Optional<SourceRow> findByCode(String code) {
        return jdbc.sql(SELECT + " where code = :code").param("code", code).query(sourceMapper).optional();
    }

    List<SourceRow> findEnabled() {
        return jdbc.sql(SELECT + " where enabled order by code").query(sourceMapper).list();
    }

    List<TargetRow> enabledTargets(UUID sourceId) {
        return jdbc.sql("""
                select id, identifier, company_id from source_targets
                where source_id = :sourceId and enabled order by identifier
                """)
                .param("sourceId", sourceId)
                .query((rs, row) -> new TargetRow(rs.getObject("id", UUID.class), rs.getString("identifier"),
                        rs.getObject("company_id", UUID.class)))
                .list();
    }

    void recordRun(UUID sourceId, Instant finishedAt, SourceHealth health) {
        jdbc.sql("update sources set last_run_at = :at, health = :health, updated_at = now() where id = :id")
                .param("at", OffsetDateTime.ofInstant(finishedAt, ZoneOffset.UTC))
                .param("health", health.name())
                .param("id", sourceId)
                .update();
    }

    IngestionProperties.Defaults defaults() {
        return properties.defaults();
    }
}
