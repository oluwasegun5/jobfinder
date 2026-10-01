package com.jobfinder.core.ingestion;

import java.util.UUID;

/**
 * Published inside the ingestion transaction whenever a job is created or refreshed. It does not say what changed:
 * listeners (the embeddings module) decide from the stored job whether the change matters to them, and act only
 * after the transaction has committed.
 */
public record JobContentChanged(UUID jobId) {
}
