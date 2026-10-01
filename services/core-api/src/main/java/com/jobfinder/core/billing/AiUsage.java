package com.jobfinder.core.billing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One billed AI call, as reported by ai-service.
 *
 * @param requestKey  idempotency key, unique per provider call (ai-service's call id): recording the same key twice
 *                    changes nothing, so redeliveries and retried write-backs never charge twice
 * @param userId      the user the call is attributed to; null for system work such as job embeddings
 * @param feature     for example {@code parse_resume}, {@code embed_job}, {@code embed_resume}
 * @param costUsd     the provider cost in US dollars, computed by ai-service from its pinned price list
 * @param pricingVersion which version of that price list produced {@code costUsd} (may be null if unknown)
 */
public record AiUsage(String requestKey, UUID userId, String feature, String provider, String model,
        long inputTokens, long outputTokens, BigDecimal costUsd, long latencyMs, String promptVersion,
        String pricingVersion, AiCallStatus status) {
}
