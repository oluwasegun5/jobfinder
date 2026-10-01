package com.jobfinder.core.ingestion;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of "which sources list this job and what must we credit": the ingestion module's third public
 * entry point, for the search and job pages (P2.6 onwards). Ingestion stores the attribution; displaying it
 * is the caller's job.
 */
public interface JobListingService {

    /**
     * The listings of each job, oldest first (the first one is the source that owns the job's content). A job
     * with no listing, or an unknown id, is simply absent from the map.
     */
    Map<UUID, List<JobListing>> listingsOf(Collection<UUID> jobIds);
}
