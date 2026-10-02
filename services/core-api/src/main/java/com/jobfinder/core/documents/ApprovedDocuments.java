package com.jobfinder.core.documents;

import java.util.Optional;
import java.util.UUID;

/**
 * The read side of approved documents, for modules that render them or attach them to an application. Only
 * approved documents are visible here: a draft is the user's work in progress and nobody else's business.
 */
public interface ApprovedDocuments {

    /** The approved document with this id, if it exists and belongs to {@code userId}. */
    Optional<ApprovedDocument> approved(UUID userId, UUID documentId);

    /** Whether the user owns a document with this id in any state (a draft included); never true for someone else's. */
    boolean exists(UUID userId, UUID documentId);
}
