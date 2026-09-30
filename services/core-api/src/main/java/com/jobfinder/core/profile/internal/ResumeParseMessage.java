package com.jobfinder.core.profile.internal;

import java.util.UUID;

/**
 * The queued request to parse one uploaded CV. It names the resume and the version to fill in and
 * carries nothing else: the file stays in object storage and nothing personal travels through the
 * broker. Delivery is at-least-once, so handling must be idempotent (see {@link ResumeParseWorker}).
 */
record ResumeParseMessage(UUID resumeId, UUID userId, int versionNumber) {
}
