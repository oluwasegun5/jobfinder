package com.jobfinder.core.ingestion;

/** How an ingestion run ended (or {@code RUNNING} while it has not). */
public enum IngestionRunStatus {
    RUNNING,
    /** Every target was fetched (including a source with no targets at all). */
    SUCCEEDED,
    /** Some targets failed, others did not. */
    PARTIAL,
    /** Every target failed. */
    FAILED
}
