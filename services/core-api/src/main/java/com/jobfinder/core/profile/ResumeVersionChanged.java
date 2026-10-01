package com.jobfinder.core.profile;

import java.util.UUID;

/**
 * Published inside the transaction that gives a resume version its structured content: the parser filling the upload
 * version, or the user saving an edit. Listeners (the embeddings module) act only after the commit and read the
 * stored content themselves, so nothing personal travels in the event.
 */
public record ResumeVersionChanged(UUID resumeVersionId) {
}
