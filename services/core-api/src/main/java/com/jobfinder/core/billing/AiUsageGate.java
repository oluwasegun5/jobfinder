package com.jobfinder.core.billing;

import java.util.UUID;

/**
 * The per-user daily cap. A pre-flight check: call {@link #requireAllowance} BEFORE a user-attributed AI call. The
 * check compares what the user already spent today (UTC) with the cap; a call that is in flight is not counted
 * until its usage is recorded, so concurrent calls can overshoot the cap by what they cost (a documented, bounded
 * overshoot, ADR 0025).
 */
public interface AiUsageGate {

    /** @throws AiDailyCapReachedException (HTTP 429, code {@code ai_daily_cap_reached}) when the cap is used up */
    void requireAllowance(UUID userId, String feature);

    Allowance allowance(UUID userId);
}
