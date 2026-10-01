package com.jobfinder.core.ingestion;

/**
 * One posting exactly as the source returned it. {@code externalId} is the source's own id for the
 * posting (stable across fetches); {@code payload} is the posting as a JSON document, stored
 * untouched so it can be reprocessed when normalization improves. Adapters do no normalizing.
 */
public record RawPosting(String externalId, String payload) {

    public RawPosting {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
        if (externalId.length() > 255) {
            throw new IllegalArgumentException("externalId must be at most 255 characters");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
    }
}
