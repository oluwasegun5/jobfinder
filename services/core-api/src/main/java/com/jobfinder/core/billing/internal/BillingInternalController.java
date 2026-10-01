package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.RecordOutcome;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Service-to-service endpoint (service token, like {@code /internal/v1/embeddings}; not in the public OpenAPI
 * document). ai-service reports here the calls it made whose results it could not hand over, so their cost is
 * still recorded. Idempotent on {@code callId}.
 */
@RestController
@RequestMapping("/internal/v1/billing")
class BillingInternalController {

    static final int MAX_BATCH = 200;

    /** Token counts and latency are optional (an embedding has no output tokens); a missing one counts as 0. */
    record UsageEntry(@NotNull UUID callId, UUID userId, @NotBlank @Size(max = 60) String feature,
            @NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 100) String model,
            @Min(0) Long inputTokens, @Min(0) Long outputTokens, @NotNull @DecimalMin("0") BigDecimal costUsd,
            @Min(0) Long latencyMs, @Size(max = 100) String promptVersion, @Size(max = 40) String pricingVersion) {
    }

    record UsageRequest(@NotEmpty @Size(max = MAX_BATCH) List<@Valid @NotNull UsageEntry> usage) {
    }

    record UsageResponse(int recorded, int duplicates) {
    }

    private final LedgerService ledger;

    BillingInternalController(LedgerService ledger) {
        this.ledger = ledger;
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    /** Records calls that were billed but whose output was lost; each is stored once, as FAILED. */
    @PutMapping("/usage")
    UsageResponse usage(@Valid @RequestBody UsageRequest request) {
        int recorded = 0;
        int duplicates = 0;
        for (UsageEntry e : request.usage()) {
            RecordOutcome outcome = ledger.record(new AiUsage("ai-service:" + e.callId(), e.userId(), e.feature(),
                    e.provider(), e.model(), orZero(e.inputTokens()), orZero(e.outputTokens()), e.costUsd(),
                    orZero(e.latencyMs()),
                    e.promptVersion(), e.pricingVersion(), AiCallStatus.FAILED));
            if (outcome == RecordOutcome.RECORDED) {
                recorded++;
            } else {
                duplicates++;
            }
        }
        return new UsageResponse(recorded, duplicates);
    }
}
