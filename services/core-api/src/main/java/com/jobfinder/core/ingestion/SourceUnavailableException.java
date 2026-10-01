package com.jobfinder.core.ingestion;

/**
 * Thrown by {@link IngestionService#runNow} for a source that is registered but cannot run, usually because
 * its API key is not configured. The message says why and never contains a credential.
 */
public class SourceUnavailableException extends RuntimeException {

    private final String sourceCode;

    public SourceUnavailableException(String sourceCode, String reason) {
        super(sourceCode + " is unavailable: " + reason);
        this.sourceCode = sourceCode;
    }

    public String sourceCode() {
        return sourceCode;
    }
}
