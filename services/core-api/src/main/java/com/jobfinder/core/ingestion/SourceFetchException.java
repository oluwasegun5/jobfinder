package com.jobfinder.core.ingestion;

/**
 * Thrown by an adapter when a fetch fails. The pipeline's reaction depends on {@link #retryable()}:
 *
 * <ul>
 * <li><b>retryable</b> (timeout, 5xx, 429, connection reset): retried with backoff, and counted
 * against the source's circuit breaker, because it says the source itself is unwell;
 * <li><b>permanent</b> (404 for a board token that no longer exists, a response that cannot be
 * parsed): not retried and not counted against the breaker, because it is about one target, not
 * the source. It is still recorded as an error in the run.
 * </ul>
 */
public class SourceFetchException extends RuntimeException {

    private final boolean retryable;

    private SourceFetchException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public static SourceFetchException transientFailure(String message, Throwable cause) {
        return new SourceFetchException(message, cause, true);
    }

    public static SourceFetchException permanentFailure(String message, Throwable cause) {
        return new SourceFetchException(message, cause, false);
    }

    public boolean retryable() {
        return retryable;
    }
}
