package com.jobfinder.core.activation;

import java.time.Instant;
import java.util.UUID;

/**
 * Milestone M4: the user looked at their matches. Published by jobs for the first feed view of each UTC day in which
 * the feed showed the user matches (VIEWED rows written), so a user whose first look fell on the day of M1 still reaches
 * M4 the next day they look.
 *
 * @param userId    the user
 * @param at        when the matches were viewed
 * @param firstEver whether this is the user's first view of matches ever (the funnel step)
 */
public record FirstMatchesViewed(UUID userId, Instant at, boolean firstEver) {
}
