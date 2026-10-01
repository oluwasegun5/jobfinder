package com.jobfinder.core.billing;

public enum RecordOutcome {
    /** A new call row was written (and the user debited, if the call has an owner and a cost). */
    RECORDED,
    /** A call with the same request key was already recorded; nothing changed. */
    DUPLICATE
}
