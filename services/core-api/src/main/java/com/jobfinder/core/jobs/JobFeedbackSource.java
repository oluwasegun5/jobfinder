package com.jobfinder.core.jobs;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the feed (docs/adr/0027-feed-and-feedback.md) reads from the jobs module: the user's own saves, hides and
 * applications as ranking signals, a few features of any job to compare against them, and the display card of a job.
 * Read-only; {@code user_job_actions} stays owned by {@code jobs}, whose endpoints write it.
 */
public interface JobFeedbackSource {

    /**
     * The user's signals with {@code after < at <= until}: for each of {@link JobAction#SAVED},
     * {@link JobAction#HIDDEN} and {@link JobAction#APPLIED}, the newest {@code perActionLimit}, newest first.
     */
    List<JobSignal> signals(UUID userId, Instant after, Instant until, int perActionLimit);

    /** Title, company and the user's saved / applied state of these jobs; unknown ids are left out. */
    Map<UUID, JobFeatures> features(UUID userId, Collection<UUID> jobIds);

    /** The display cards of these jobs (with the user's saved / applied flags); unknown ids are left out. */
    Map<UUID, JobCard> cards(UUID userId, Collection<UUID> jobIds);
}
