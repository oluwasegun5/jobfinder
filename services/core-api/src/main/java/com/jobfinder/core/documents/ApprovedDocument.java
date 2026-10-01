package com.jobfinder.core.documents;

import java.time.Instant;
import java.util.UUID;

/**
 * An approved document, as it was when approved and as it will always be.
 *
 * @param type         {@code TAILORED_RESUME}
 * @param contentJson  the document: for a tailored resume, the structured resume JSON (contact block included)
 * @param jobId        the job it was written for; the job itself may no longer exist
 */
public record ApprovedDocument(UUID id, String type, UUID jobId, String jobTitle, String jobCompany,
        String contentJson, Instant approvedAt) {
}
