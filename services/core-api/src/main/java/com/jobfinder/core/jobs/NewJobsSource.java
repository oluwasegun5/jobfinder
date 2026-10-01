package com.jobfinder.core.jobs;

import java.time.Instant;
import java.util.UUID;

/**
 * The jobs that appeared since a point in time and match a saved search, for the notifications module
 * (docs/adr/0028-notifications.md). Read-only, like the rest of this module's public API.
 */
public interface NewJobsSource {

    /**
     * Active jobs first seen after {@code after} and up to {@code until} that match {@code criteria}, newest first,
     * at most {@code limit} of them, with the total that matched. Jobs the user hid or marked as applied are left out,
     * as the search leaves out hidden ones. "First seen" is when ingestion stored the job, not the date the source gave,
     * so a job posted last week but only found today is new today.
     */
    NewJobs newSince(UUID userId, JobSearchCriteria criteria, Instant after, Instant until, int limit);
}
