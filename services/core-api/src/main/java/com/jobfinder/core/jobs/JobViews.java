package com.jobfinder.core.jobs;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Records that the user saw jobs in their feed: a {@code VIEWED} row per job in {@code user_job_actions} (allowed since
 * V20; not a ranking signal), and the activation milestone {@code activation.FirstMatchesViewed} for the first view of
 * each UTC day (docs/adr/0042-trial-credit-abuse-gate.md, M4).
 */
public interface JobViews {

    /** Records that {@code userId} was shown {@code jobIds} at {@code at}. Unknown job ids are ignored. */
    void recordFeedView(UUID userId, Collection<UUID> jobIds, Instant at);
}
