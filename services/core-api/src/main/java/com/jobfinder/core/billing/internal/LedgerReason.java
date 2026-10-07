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
    REFUND_ADJUSTMENT
}
