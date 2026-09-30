package com.jobfinder.core.profile.internal;

/** A CV could not be parsed. {@code retryable} says whether trying again might succeed. */
class ParseFailure extends RuntimeException {

    private final ParseFailureReason reason;
    private final boolean retryable;

    ParseFailure(ParseFailureReason reason, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    static ParseFailure permanent(ParseFailureReason reason, String message) {
        return new ParseFailure(reason, false, message, null);
    }

    static ParseFailure transientFailure(String message, Throwable cause) {
        return new ParseFailure(ParseFailureReason.PARSER_UNAVAILABLE, true, message, cause);
    }

    ParseFailureReason reason() {
        return reason;
    }

    boolean retryable() {
        return retryable;
    }
}
