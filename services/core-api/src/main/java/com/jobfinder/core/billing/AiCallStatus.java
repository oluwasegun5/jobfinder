package com.jobfinder.core.billing;

/** What became of an AI call's output. Failed calls were still billed by the provider, so they are recorded too. */
public enum AiCallStatus {
    /** The output was used. */
    SUCCEEDED,
    /** The call was billed but its output was discarded (failed validation, refused, or could not be stored). */
    FAILED
}
