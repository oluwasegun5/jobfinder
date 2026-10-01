package com.jobfinder.core.ingestion.internal;

/** The conditions that raise a source alert (ADR 0024). */
enum AlertRule {
    /** A clean run fetched nothing from a source whose previous clean run fetched something. */
    ZERO_JOBS("returned no jobs"),
    /** More than the configured share of a run's targets failed. */
    ERROR_RATE("has a high error rate");

    private final String phrase;

    AlertRule(String phrase) {
        this.phrase = phrase;
    }

    /** Completes "SOURCE ...": used in the subject line. */
    String phrase() {
        return phrase;
    }
}
