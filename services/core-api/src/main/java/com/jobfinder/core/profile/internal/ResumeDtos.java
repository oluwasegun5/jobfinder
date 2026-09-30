package com.jobfinder.core.profile.internal;

import java.time.Instant;
import java.util.UUID;

/** Request/response shapes for the resume endpoints. */
final class ResumeDtos {

    private ResumeDtos() {
    }

    record ResumeResponse(UUID id, String label, ResumeFormat fileType, long sizeBytes, boolean primary,
            ParseStatus parseStatus, Instant createdAt) {

        static ResumeResponse from(Resume resume) {
            return new ResumeResponse(resume.getId(), resume.getLabel(), resume.getFileType(),
                    resume.getSizeBytes(), resume.isPrimary(), resume.getParseStatus(), resume.getCreatedAt());
        }
    }

    /** A short-lived, pre-signed link straight to the file in object storage. */
    record DownloadUrlResponse(String url, Instant expiresAt) {
    }
}
