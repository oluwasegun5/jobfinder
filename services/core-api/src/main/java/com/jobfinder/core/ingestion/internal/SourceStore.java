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

import com.jobfinder.core.ingestion.SourceAttribution;
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

    /** Writes the credit the adapter's terms require; the adapter owns the wording, so this always overwrites. */
    void updateAttribution(String code, SourceAttribution attribution) {
        jdbc.sql("""
                update sources set attribution_name = :name, attribution_text = :text, attribution_url = :url,
                                   attribution_notes = :notes, updated_at = now()
                where code = :code
                """)
                .param("name", attribution.name())
                .param("text", attribution.text())
                .param("url", attribution.url())
                .param("notes", attribution.notes())
                .param("code", code)
                .update();
    }

    /** Merges {@code patch} (a JSON object) into the source's config; keys already set keep their value. */
    void applyDefaultConfig(String code, String patch) {
        jdbc.sql("update sources set config = cast(:patch as jsonb) || config, updated_at = now() where code = :code")
                .param("patch", patch)
                .param("code", code)
                .update();
    }

    Optional<SourceRow> findByCode(String code) {
        return jdbc.sql(SELECT + " where code = :code").param("code", code).query(sourceMapper).optional();
    }

    List<SourceRow> findAll() {
        return jdbc.sql(SELECT + " order by code").query(sourceMapper).list();
    }

    /** @return whether a source with the code exists */
    boolean setEnabled(String code, boolean enabled) {
        return jdbc.sql("update sources set enabled = :enabled, updated_at = now() where code = :code")
                .param("enabled", enabled)
                .param("code", code)
                .update() == 1;
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
