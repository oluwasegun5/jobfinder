package com.jobfinder.core.profile.internal;

import java.util.UUID;

/** Published inside the upload transaction; parsing is queued only once it has committed. */
record ResumeUploaded(UUID resumeId, UUID userId, int versionNumber) {
}
