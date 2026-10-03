package com.jobfinder.core.jobs;

import java.util.List;
import java.util.UUID;

/**
 * Finds jobs by the link people apply through (docs/adr/0035-chrome-extension.md): the browser extension knows the URL of
 * the application form the user has open and needs the job it belongs to. Jobs stay owned by ingestion; this only reads.
 */
public interface JobApplyLinks {

    /**
     * Jobs whose stored apply link contains {@code fragment} (case-insensitive, taken literally: no wildcards), active
     * ones first, at most {@code limit}. A coarse pre-filter: the caller compares the links properly.
     */
    List<ApplyLink> withApplyUrlContaining(String fragment, int limit);

    /** A job and the link it is applied through, as ingestion stored it. */
    record ApplyLink(UUID jobId, String title, String company, String applyUrl) {
    }
}
