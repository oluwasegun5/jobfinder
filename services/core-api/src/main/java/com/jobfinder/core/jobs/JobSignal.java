package com.jobfinder.core.jobs;

import java.time.Instant;
import java.util.UUID;

/** One thing the user did with one job, with the job's company and title for comparing other jobs to it. */
public record JobSignal(UUID jobId, UUID companyId, String title, JobAction action, Instant at) {
}
