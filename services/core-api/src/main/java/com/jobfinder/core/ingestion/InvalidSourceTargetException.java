package com.jobfinder.core.ingestion;

/** A source target's identifier or company name cannot be used. The message says why and is safe to show. */
public class InvalidSourceTargetException extends RuntimeException {

    public InvalidSourceTargetException(String message) {
        super(message);
    }
}
