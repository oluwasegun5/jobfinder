package com.jobfinder.core.activation;

import java.time.LocalDate;
import java.util.List;

/**
 * The funnel for the users who signed up from {@code from} to {@code to} (UTC days, both included). Counts only: no
 * user is identified.
 *
 * @param steps   one entry per {@link FunnelStep}, in order
 * @param cohorts the same counts per signup week (UTC, weeks start on Monday), oldest first; weeks without a signup are
 *                left out
 */
public record FunnelReport(LocalDate from, LocalDate to, List<StepCount> steps, List<WeekCohort> cohorts) {

    /**
     * Users who reached {@code step} and every step before it. {@code fromPrevious} and {@code overall} are ratios from
     * 0 to 1, null when the step before (or the signup count) is zero, and for the first step's {@code fromPrevious}.
     */
    public record StepCount(FunnelStep step, long count, Double fromPrevious, Double overall) {
    }

    /** Counts per step (in {@link FunnelStep} order) of the users who signed up in the week starting {@code weekStart}. */
    public record WeekCohort(LocalDate weekStart, List<Long> counts) {
    }
}
