package com.jobfinder.core.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Totals for one group of AI calls. Which of {@code day}, {@code feature} and {@code model} are set depends on the
 * grouping; the others are null.
 */
public record CostRow(LocalDate day, String feature, String model, long calls, long failedCalls, long inputTokens,
        long outputTokens, BigDecimal costUsd) {
}
