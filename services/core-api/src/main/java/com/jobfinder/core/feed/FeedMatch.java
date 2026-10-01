package com.jobfinder.core.feed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One job of the feed as a notification shows it.
 *
 * @param feedScore  the model score moved by the user's own feedback, 0 to 100 (what the feed ranks by)
 * @param matchScore the model's own score, 0 to 100
 * @param strengths  why the model scored it well, as the model wrote it: untrusted text, escape it before showing it
 * @param scoredAt   when the model scored it
 */
public record FeedMatch(UUID jobId, double feedScore, int matchScore, List<String> strengths, Instant scoredAt) {
}
