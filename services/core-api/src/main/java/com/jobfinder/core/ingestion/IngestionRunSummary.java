package com.jobfinder.core.ingestion;

import java.util.UUID;

/**
 * What one run did. {@code fetched} postings were received; {@code created} and {@code updated}
 * say how many raw postings were stored for the first time and refreshed; {@code errors} is the
 * number of targets that failed.
 */
public record IngestionRunSummary(UUID runId, String sourceCode, IngestionRunStatus status, int fetched,
        int created, int updated, int expired, int errors) {
}
