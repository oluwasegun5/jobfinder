package com.jobfinder.core.ingestion;

/** Whether the scheduler will pick a source up, and if not, why. */
public enum SourceSchedule {
    /** Enabled and able to run: it is started when due. */
    SCHEDULED,
    /** Switched off by an admin. */
    DISABLED,
    /** Enabled, but it cannot run (its API key is not configured); see {@code unavailableReason}. */
    UNAVAILABLE,
    /** Enabled, but the scheduler is switched off for the whole application. */
    SCHEDULER_OFF
}
