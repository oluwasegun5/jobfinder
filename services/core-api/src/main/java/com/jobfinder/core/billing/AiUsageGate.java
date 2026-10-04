package com.jobfinder.core.billing;

import java.util.UUID;

/**
 * The pre-flight check before a user-attributed AI call: call {@link #requireAllowance} BEFORE the call. A user may
 * spend when their credit balance is above zero AND today's cap (UTC) is not used up. A user on the Free plan who has
 * not yet received this month's credits is granted them first (so a missed monthly job strands nobody).
 *
 * <p>The check is before the call: a call in flight is not counted until its usage is recorded, so concurrent calls
 * can overshoot the cap, and a call that was allowed may take the balance below zero, by at most what the in-flight
 * calls cost (a documented, bounded overshoot, ADR 0025 and ADR 0036). Usage that was already billed is always
 * recorded, whatever the balance.
 */
public interface AiUsageGate {

    /**
     * @throws InsufficientCreditsException (HTTP 402, code {@code insufficient_credits}) when the balance is spent
     * @throws AiDailyCapReachedException   (HTTP 429, code {@code ai_daily_cap_reached}) when the cap is used up
     */
    void requireAllowance(UUID userId, String feature);

    /** Today's cap and usage. Says nothing about the credit balance; see {@link #status}. */
    Allowance allowance(UUID userId);

    /** What {@link #requireAllowance} would do right now, without throwing (and with the same lazy monthly grant). */
    GateStatus status(UUID userId);
}
