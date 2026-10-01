package com.jobfinder.core.ingestion;

import java.util.UUID;

/**
 * What one run did. {@code fetched} postings were received; {@code created} is how many new jobs they
 * produced, {@code updated} how many existing jobs they refreshed or were merged into as another
 * listing, and {@code expired} how many jobs the expiry rules closed; {@code errors} is the number of
 * targets that failed.
 */
public record IngestionRunSummary(UUID runId, String sourceCode, IngestionRunStatus status, int fetched,
        int created, int updated, int expired, int errors) {
}
