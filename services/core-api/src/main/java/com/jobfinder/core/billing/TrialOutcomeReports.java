package com.jobfinder.core.billing;

import java.time.Instant;

/** Read side of the trial gate's decisions for the admin funnel (docs/adr/0045-activation-funnel.md). */
public interface TrialOutcomeReports {

    /**
     * How the trials of the accounts created in [{@code from}, {@code to}) were decided. Aggregates only: no account,
     * score or signal is returned. Accounts from before the trial are not counted.
     */
    TrialOutcomes outcomes(Instant from, Instant to);

    /** Counts of accounts by the gate's decision. */
    record TrialOutcomes(long granted, long hold, long denied) {
    }
}
