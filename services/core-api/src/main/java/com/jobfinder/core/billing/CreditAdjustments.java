package com.jobfinder.core.billing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Manual corrections to a user's credit balance: the refund flow of docs/adr/0036-plans-credits-and-payments.md.
 * Refund and dispute webhooks are logged, not applied; an admin who has refunded a payment at the provider takes the
 * credits back here. One call writes one {@code REFUND_ADJUSTMENT} ledger line, atomically and under the user's ledger
 * lock, and is idempotent on the key.
 */
public interface CreditAdjustments {

    /** What happened to an adjustment request. */
    enum Result {
        /** The line was written by this call. */
        APPLIED,
        /** The same key, user and delta were written earlier: nothing changed. */
        REPLAYED,
        /** The key was used before for another user or another delta: nothing changed. */
        KEY_CONFLICT,
        /** There is no such user: nothing changed. */
        USER_NOT_FOUND
    }

    /** {@code balance} is the user's balance after the call (null when the user does not exist). */
    record Outcome(Result result, BigDecimal balance) {
    }

    /**
     * Takes credits off a user's balance (the balance may go below zero: later grants pay it off).
     *
     * @param delta          a negative number of credits, at most six decimals
     * @param idempotencyKey chosen by the caller; the same key never writes twice
     * @param reason         why, for the audit trail; no personal data
     * @throws IllegalArgumentException when {@code delta} is not negative, or the key or reason is blank or too long
     */
    Outcome adjust(UUID userId, BigDecimal delta, String idempotencyKey, String reason);
}
