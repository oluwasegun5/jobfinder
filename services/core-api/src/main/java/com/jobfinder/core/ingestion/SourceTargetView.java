package com.jobfinder.core.ingestion;

import java.util.UUID;

/**
 * A source target as {@link SourceTargetService#addTarget} leaves it. {@code created} is false when the
 * target was already there.
 */
public record SourceTargetView(UUID id, String sourceCode, String identifier, String companyName, boolean enabled,
        boolean created) {
}
