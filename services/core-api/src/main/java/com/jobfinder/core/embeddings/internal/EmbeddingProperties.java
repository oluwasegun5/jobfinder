package com.jobfinder.core.embeddings.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embeddings pipeline ({@code app.embeddings.*}). {@code model} and {@code dimension} are the pinned embedding
 * space: ai-service must use the same two values (it checks them on every request and core-api refuses vectors
 * made with anything else), and a stored vector counts as stale as soon as its model differs from {@code model}.
 * {@code dimension} must also match the {@code vector(n)} column type (checked at startup).
 */
@ConfigurationProperties("app.embeddings")
record EmbeddingProperties(
        @DefaultValue("voyage-4") String model,
        @DefaultValue("1024") int dimension,
        @DefaultValue("24000") int maxInputChars,
        @DefaultValue("jobs.embed") String jobsQueue,
        @DefaultValue("jobs.embed.dlq") String jobsDeadLetterQueue,
        @DefaultValue("resumes.embed") String resumesQueue,
        @DefaultValue("resumes.embed.dlq") String resumesDeadLetterQueue,
        @DefaultValue("500") int backfillPageSize,
        @DefaultValue("true") boolean publishEnabled) {

    EmbeddingProperties {
        if (model == null || model.isBlank() || model.length() > 100) {
            throw new IllegalArgumentException("app.embeddings.model (EMBEDDING_MODEL) must be set, at most 100 characters");
        }
        if (dimension < 1 || dimension > 2000) {
            throw new IllegalArgumentException("app.embeddings.dimension must be between 1 and 2000 (the HNSW limit)");
        }
        if (maxInputChars < 100) {
            throw new IllegalArgumentException("app.embeddings.max-input-chars must be at least 100");
        }
        if (backfillPageSize < 1) {
            throw new IllegalArgumentException("app.embeddings.backfill-page-size must be at least 1");
        }
    }

    String queue(EmbeddingKind kind) {
        return kind == EmbeddingKind.JOB ? jobsQueue : resumesQueue;
    }
}
