package com.jobfinder.core.profile;

import java.util.UUID;

/**
 * The latest version of a resume as an exporter sees it.
 *
 * @param versionId       the resume version: immutable in meaning (an edit made later is a new version or replaces
 *                        the content of the latest one, so exporters must also key on a hash of the content)
 * @param label           the user's label for the resume
 * @param structuredJson  the structured content (contact block included), or null if there is none yet
 */
public record ResumeSnapshot(UUID resumeId, UUID versionId, String label, String structuredJson) {
}
