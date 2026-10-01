package com.jobfinder.core.jobs;

import java.util.UUID;

/** What the feed compares a candidate job by, and the caller's own state of it. */
public record JobFeatures(UUID jobId, UUID companyId, String title, boolean saved, boolean applied) {
}
