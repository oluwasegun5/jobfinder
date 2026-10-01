package com.jobfinder.core.billing;

import java.time.LocalDate;

/** Read side of the ledger for the admin cost dashboard. */
public interface AiCostReports {

    /** The longest range a report may cover, in days. */
    int MAX_RANGE_DAYS = 366;

    /**
     * @throws IllegalArgumentException if {@code to} is before {@code from} or the range is longer than
     *                                  {@link #MAX_RANGE_DAYS}
     */
    CostReport report(LocalDate from, LocalDate to);
}
