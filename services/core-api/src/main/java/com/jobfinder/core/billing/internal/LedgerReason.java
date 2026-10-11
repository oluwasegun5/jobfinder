package com.jobfinder.core.billing.internal;

/** Why a ledger line exists (the {@code credit_ledger.reason} column). */
enum LedgerReason {
    /** An AI call's cost (negative). */
    AI_USAGE,
    /** Credits a plan grants for a period (positive). */
    PLAN_GRANT,
    /** Unused plan credits that did not roll over into the next grant (negative). */
    PLAN_EXPIRY,
    /** A purchased credit pack (positive, never expires). */
    TOPUP,
    /** A manual correction, for example after a refund (either sign). */
    REFUND_ADJUSTMENT,
    /** One tranche of the one-time trial, released at a milestone (positive; docs/adr/0042). */
    TRIAL_GRANT,
    /** Trial credits left unspent when the trial's validity ends (negative). */
    TRIAL_EXPIRY
}
