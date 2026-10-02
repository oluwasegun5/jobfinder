package com.jobfinder.core.jobs;

import java.util.Optional;
import java.util.UUID;

/**
 * The read side of the job and company tables for interview prep (docs/adr/0033-interview-prep.md). Jobs stay owned by
 * ingestion; this only reads.
 */
public interface JobBriefSource {

    /** The job and its company record, whatever the job's status (a user may prepare for a job that has since closed). */
    Optional<JobForBrief> job(UUID id);
}
