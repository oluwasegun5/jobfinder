package com.jobfinder.core.feed;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The best of the user's "For you" feed as a list, for the digests and alerts of the notifications module
 * (docs/adr/0028-notifications.md). It is the feed's own ranking (the cached model score moved by the user's saves,
 * hides and applications, ADR 0027), so a hidden job never comes back and one the user applied to is gone. It reads the
 * score cache only: it never calls the model and spends no AI allowance.
 */
public interface FeedMatches {

    /**
     * Matches the model scored after {@code scoredAfter} whose feed score (0 to 100) is at least {@code minScore},
     * best first, at most {@code limit}, leaving out {@code exclude}. Estimates (jobs the model has not scored) are
     * never returned: their scale is not comparable to a model score. Empty for a user with no resume, no saved
     * preferences or nothing recalled.
     */
    List<FeedMatch> strongMatches(UUID userId, Instant scoredAfter, double minScore, Collection<UUID> exclude,
            int limit);
}
