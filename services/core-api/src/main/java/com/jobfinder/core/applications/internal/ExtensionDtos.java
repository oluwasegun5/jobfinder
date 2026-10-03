package com.jobfinder.core.applications.internal;

import java.util.UUID;

import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationStatus;

/** The shapes of the endpoint the browser extension reads (docs/adr/0035-chrome-extension.md). */
final class ExtensionDtos {

    private ExtensionDtos() {
    }

    /**
     * What JobFinder knows about the application form the user has open. {@code applicationId} and
     * {@code applicationStatus} are present only when this user already tracks the job; {@code packSummary} only when
     * this user has a pack for it. Neither ever describes anyone else's.
     */
    record ApplyContext(ApplyContextJob job, UUID applicationId, ApplicationStatus applicationStatus,
            ApplyContextPack packSummary) {
    }

    record ApplyContextJob(UUID id, String title, String company) {
    }

    /** {@code status}: GENERATING, COMPLETE, PARTIAL or FAILED. */
    record ApplyContextPack(UUID id, String status, int version) {
    }
}
