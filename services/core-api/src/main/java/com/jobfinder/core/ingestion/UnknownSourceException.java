package com.jobfinder.core.ingestion;

/** There is no registered source with the given code. */
public class UnknownSourceException extends RuntimeException {

    public UnknownSourceException(String sourceCode) {
        super("Unknown ingestion source: " + sourceCode);
    }
}
