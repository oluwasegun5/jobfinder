package com.jobfinder.core.documents;

import java.util.Optional;
import java.util.UUID;

/**
 * The read side of application packs, for modules that link a pack to something else (the application tracker links
 * the pack a user applied with). Packs are found through their owner only.
 */
public interface ApplicationPacks {

    /** The id of the job the pack was made for, if the pack exists and belongs to {@code userId}; empty otherwise. */
    Optional<UUID> jobOf(UUID userId, UUID packId);
}
