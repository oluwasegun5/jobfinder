package com.jobfinder.core.activation;

import java.time.LocalDate;

/** Read side of the activation funnel for the admin view (docs/adr/0046-activation-funnel.md). */
public interface ActivationFunnelReports {

    /** The longest range a report may cover, in days. */
    int MAX_RANGE_DAYS = 366;

    /**
     * @throws IllegalArgumentException if {@code to} is before {@code from} or the range is longer than
     *                                  {@link #MAX_RANGE_DAYS}
     */
    FunnelReport report(LocalDate from, LocalDate to);
}
