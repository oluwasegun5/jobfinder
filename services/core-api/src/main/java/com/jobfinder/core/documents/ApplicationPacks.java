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

    /**
     * The user's most recent pack for the job (any status), if there is one; never someone else's. For the browser
     * extension, which needs to know whether a pack exists and in what state (docs/adr/0035-chrome-extension.md).
     */
    Optional<PackSummary> latestFor(UUID userId, UUID jobId);

    /** {@code status} is {@code GENERATING}, {@code COMPLETE}, {@code PARTIAL} or {@code FAILED}. */
    record PackSummary(UUID id, String status, int version) {
    }
}
