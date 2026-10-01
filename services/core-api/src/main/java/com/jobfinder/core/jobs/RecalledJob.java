package com.jobfinder.core.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A job found by recall: its cosine similarity to the query vector, the skills it lists and when it was posted. */
public record RecalledJob(UUID jobId, double similarity, List<String> skills, Instant postedAt) {
}
