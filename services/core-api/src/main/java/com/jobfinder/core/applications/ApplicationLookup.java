package com.jobfinder.core.applications;

import java.util.Optional;
import java.util.UUID;

/**
 * The one thing other modules may ask of the application tracker (docs/adr/0034-mock-interview.md): whether an
 * application is the user's, and which job it was for. A mock interview session can be tied to an application; it
 * must not be tied to someone else's.
 */
public interface ApplicationLookup {

    /**
     * The application with this id if it belongs to the user. Someone else's application, and one that does not exist,
     * are the same: empty.
     */
    Optional<ApplicationRef> owned(UUID userId, UUID applicationId);

    /** {@code jobId} is null for an application entered by hand for a job found elsewhere. */
    record ApplicationRef(UUID id, UUID jobId) {
    }
}
