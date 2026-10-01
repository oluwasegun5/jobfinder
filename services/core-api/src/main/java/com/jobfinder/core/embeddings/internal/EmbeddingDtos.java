package com.jobfinder.core.embeddings.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
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

    /**
     * What one provider call cost, for the billing ledger (ADR 0025). {@code callId} is ai-service's id for the call:
     * the ledger records each id once, so a retried write-back never charges twice (older senders may leave it out,
     * see {@link EmbeddingService}). {@code userId} is null for system work (job embeddings).
     */
    record UsageRecord(UUID callId, UUID userId, @NotBlank @Size(max = 60) String feature,
            @NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 100) String model,
            @Min(0) long inputTokens, @DecimalMin("0") BigDecimal costUsd, @Min(0) long latencyMs,
            @Size(max = 40) String pricingVersion) {
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
