package com.jobfinder.core.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Who has been around lately. A user counts as active when they signed in or renewed their session (a refresh
 * token was issued) since a given instant; disabled and deleted accounts never do.
 */
public interface UserActivity {

    /**
     * Ids of active users in ascending id order, those greater than {@code afterId} first, at most {@code limit}.
     * Walk all of them by passing the last id of each page as the next {@code afterId}.
     */
    List<UUID> activeSince(Instant since, UUID afterId, int limit);
}
