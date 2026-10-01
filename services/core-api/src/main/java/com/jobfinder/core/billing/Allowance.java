package com.jobfinder.core.billing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A user's AI allowance for the current UTC day, in credits. {@code dailyCap} is null when no cap is configured
 * (then {@code remaining} is null too).
 */
public record Allowance(BigDecimal dailyCap, BigDecimal used, BigDecimal remaining, Instant resetsAt) {

    /** True when a cap is configured and today's usage has reached it. */
    public boolean exhausted() {
        return remaining != null && remaining.signum() <= 0;
    }
}
