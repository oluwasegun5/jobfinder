package com.jobfinder.core.ingestion;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the run history. {@code targets} is how many targets the run attempted (0 for a run recorded before
 * this was tracked, and while a run is in progress). {@code errorSummary} names the failed targets and why; the
 * adapters keep credentials out of it.
 */
public record IngestionRunView(UUID id, String sourceCode, IngestionRunStatus status, Instant startedAt,
        Instant finishedAt, int targets, int fetched, int created, int updated, int expired, int errors,
        String errorSummary) {
}
