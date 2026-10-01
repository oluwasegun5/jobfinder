package com.jobfinder.core.embeddings.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The wire shapes of the internal embeddings endpoints (camelCase JSON, like the rest of core-api). */
final class EmbeddingDtos {

    static final int MAX_BATCH = 200;

    private EmbeddingDtos() {
    }

    record InputsRequest(@NotNull EmbeddingKind kind, @NotEmpty @Size(max = MAX_BATCH) List<@NotNull UUID> ids) {
    }

    /** One text to embed. {@code userId} is the resume's owner (for usage records), null for jobs. */
    record InputItem(UUID id, UUID userId, String text, String inputHash) {
    }

    /** Why an id has nothing to embed: NOT_FOUND, EXPIRED (jobs), NO_CONTENT (resume versions) or UP_TO_DATE. */
    record Skipped(UUID id, String reason) {
    }

    record InputsResponse(String model, int dimension, String inputType, List<InputItem> items,
            List<Skipped> skipped) {
    }

    record ResultItem(@NotNull UUID id, @NotBlank @Size(min = 64, max = 64) String inputHash,
            @NotEmpty List<@NotNull Float> embedding) {
    }

    /** What one provider call cost; logged here until the billing ledger exists (Phase 3). */
    record UsageRecord(UUID userId, @NotBlank String feature, @NotBlank String provider, @NotBlank String model,
            long inputTokens, BigDecimal costUsd, long latencyMs) {
    }

    record ResultsRequest(@NotNull EmbeddingKind kind, @NotBlank String model, int dimension,
            @NotEmpty @Size(max = MAX_BATCH) List<@Valid ResultItem> items, List<@Valid UsageRecord> usage) {
    }

    /** {@code stale}: the text changed since the vector was computed (a newer message is queued); {@code missing}: gone. */
    record ResultsResponse(int applied, int stale, int missing) {
    }

    record Counts(int scanned, int enqueued) {
    }

    record BackfillResponse(String model, Counts jobs, Counts resumeVersions) {
    }
}
