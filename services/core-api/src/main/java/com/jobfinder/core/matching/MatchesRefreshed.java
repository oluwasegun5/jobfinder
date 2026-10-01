package com.jobfinder.core.matching;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a ranking run (the nightly batch, or a user's own request) that had the model score at least one job
 * for the user: their cached matches have changed. Listeners must not call back into matching for it (no new model
 * calls: read {@link MatchService#cachedMatches}); a failing listener never fails the run. Carries no personal data.
 *
 * @param newlyScored how many jobs the model scored in the run
 */
public record MatchesRefreshed(UUID userId, Instant at, int newlyScored) {
}
