package com.jobfinder.core.ingestion;

import java.util.UUID;

/**
 * What an adapter is asked to fetch in one call: a Greenhouse board token, a Lever company slug,
 * an aggregator search. {@code companyId} is the employer the target belongs to, when known.
 */
public record FetchTarget(UUID id, String identifier, UUID companyId) {
}
