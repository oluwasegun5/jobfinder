package com.jobfinder.core.jobs;

/**
 * What a user did with a job that feeds back into ranking (docs/adr/0027-feed-and-feedback.md). {@code VIEWED} exists in
 * the table but is not written yet and is not a signal.
 */
public enum JobAction {
    /** The user saved the job. */
    SAVED,
    /** The user hid the job: it is removed from their search and feed. */
    HIDDEN,
    /** The user says they applied. */
    APPLIED
}
