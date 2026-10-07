package com.jobfinder.core.billing;

/** Whether a user may start an AI call now, and if not, why. */
public enum GateStatus {
    /** Credits remain and today's cap is not used up. */
    OK,
    /** The credit balance is zero or less (HTTP 402 when enforced). */
    INSUFFICIENT_CREDITS,
    /** Today's cap is used up (HTTP 429 when enforced). */
    DAILY_CAP_REACHED,
    /** The user has not agreed to AI processing of their data, or withdrew it (HTTP 403 when enforced). */
    CONSENT_REQUIRED
}
