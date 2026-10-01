package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.IngestionRunStatus;

/** Writes the {@code ingestion_runs} row of each run. */
@Component
class IngestionRunStore {

    record Counts(int fetched, int created, int updated, int expired, int errors) {
    }

    private final JdbcClient jdbc;

    IngestionRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID start(UUID sourceId, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into ingestion_runs (id, source_id, started_at, status, created_at, updated_at)
                values (:id, :sourceId, :startedAt, 'RUNNING', now(), now())
                """)
                .param("id", id)
                .param("sourceId", sourceId)
                .param("startedAt", utc(startedAt))
                .update();
        return id;
    }

    void finish(UUID runId, IngestionRunStatus status, int targets, Counts counts, String errorSummary,
            Instant finishedAt) {
        jdbc.sql("""
                update ingestion_runs
                   set status = :status, finished_at = :finishedAt, targets = :targets, fetched = :fetched, created = :created,
                       updated = :updated, expired = :expired, errors = :errors, error_summary = :summary,
                       updated_at = now()
                 where id = :id
                """)
                .param("status", status.name())
                .param("finishedAt", utc(finishedAt))
                .param("targets", targets)
                .param("fetched", counts.fetched())
                .param("created", counts.created())
                .param("updated", counts.updated())
                .param("expired", counts.expired())
                .param("errors", counts.errors())
                .param("summary", errorSummary)
                .param("id", runId)
                .update();
    }

    /**
     * Closes runs of this source still marked RUNNING. Only call it while holding the source's lock:
     * then no run can really be in progress, so a RUNNING row is one whose instance died.
     */
    int closeInterrupted(UUID sourceId, Instant now) {
        return jdbc.sql("""
                update ingestion_runs
                   set status = 'FAILED', finished_at = :now, updated_at = now(),
                       error_summary = 'Interrupted: the instance running this run stopped before it finished'
                 where source_id = :sourceId and status = 'RUNNING'
                """)
                .param("now", utc(now))
                .param("sourceId", sourceId)
                .update();
    }

    /**
     * The postings fetched by the latest run before {@code before} that ended SUCCEEDED and had targets, or empty
     * if there is none: what "normal" looks like for the zero-jobs alert.
     */
    Optional<Integer> previousCleanFetched(UUID sourceId, Instant before) {
        return jdbc.sql("""
                select fetched from ingestion_runs
                 where source_id = :id and status = 'SUCCEEDED' and targets > 0 and started_at < :before
                 order by started_at desc limit 1
                """)
                .param("id", sourceId)
                .param("before", utc(before))
                .query(Integer.class)
                .optional();
    }

    /** When the last fully successful run started: the {@code since} handed to adapters. */
    Optional<Instant> lastSuccessfulStart(UUID sourceId) {
        return jdbc.sql("select max(started_at) from ingestion_runs where source_id = :id and status = 'SUCCEEDED'")
                .param("id", sourceId)
                .query((rs, row) -> {
                    OffsetDateTime at = rs.getObject(1, OffsetDateTime.class);
                    return at == null ? null : at.toInstant();
                })
                .optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
