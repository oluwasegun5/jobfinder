package com.jobfinder.core.documents.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentType;
import com.jobfinder.core.documents.internal.DocumentDtos.DraftResponse;
import com.jobfinder.core.documents.internal.DocumentDtos.JobRef;
import com.jobfinder.core.shared.ApiException;

import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;

/**
 * Request and response shapes of cover letters, screening answers and the application pack
 * (docs/adr/0031-cover-letters-and-application-pack.md).
 */
final class WritingDtos {

    private WritingDtos() {
    }

    enum Tone {
        FORMAL, WARM, CONCISE;

        String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    enum Length {
        SHORT, STANDARD, LONG;

        String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * How a letter or a set of answers is written. Every field is optional: {@code FORMAL} and {@code STANDARD} by
     * default. {@code notes} is what the user wants said or stressed (up to 1000 characters); it is treated like the job
     * text, as untrusted input, and can never add a fact the resume does not show.
     */
    record WritingRequest(Tone tone, Length length, @Size(max = 1000) String notes) {

        WritingRequest {
            tone = tone == null ? Tone.FORMAL : tone;
            length = length == null ? Length.STANDARD : length;
            notes = notes == null || notes.isBlank() ? null : notes.strip();
        }

        static WritingRequest defaults() {
            return new WritingRequest(null, null, null);
        }
    }

    /** The application pack: the options of the writing, and which parts to make (all three by default). */
    record PackRequest(Tone tone, Length length, @Size(max = 1000) String notes,
            @Size(max = 3) List<DocumentType> include) {

        PackRequest {
            tone = tone == null ? Tone.FORMAL : tone;
            length = length == null ? Length.STANDARD : length;
            notes = notes == null || notes.isBlank() ? null : notes.strip();
        }

        WritingRequest writing() {
            return new WritingRequest(tone, length, notes);
        }

        /** Omitting {@code include} means every part; giving it empty is a mistake, not "nothing". */
        List<DocumentType> parts() {
            if (include != null && include.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_part", "include must not be empty when given.");
            }
            return include == null ? List.of(DocumentType.TAILORED_RESUME, DocumentType.COVER_LETTER,
                    DocumentType.SCREENING_ANSWERS) : include.stream().distinct().toList();
        }
    }

    /** Which parts of a pack to make again; none given means every part that failed or was blocked. */
    record RetryRequest(@Size(max = 3) List<DocumentType> parts) {
    }

    enum PartState {
        /** Not made yet (the pack is being generated). */
        PENDING,
        READY,
        /** ai-service failed or was unavailable; see {@code error}. Retry it. */
        FAILED,
        /** The daily AI allowance was used up; {@code error.resetsAt} says when it comes back. Retry then. */
        BLOCKED_BY_CAP,
        /** The document was ready but the user deleted that draft. Retry makes a new one. */
        MISSING
    }

    enum PackStatus {
        GENERATING, COMPLETE, PARTIAL, FAILED
    }

    /** Why a part has no document: a stable code, a message for people, and when to try again for a cap. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PartError(String code, String message, boolean retryable, Instant resetsAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PartView(DocumentType type, PartState state, DraftResponse document, PartError error) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PackOptions(Tone tone, Length length, String notes, List<DocumentType> include) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PackResponse(UUID id, PackStatus status, JobRef job, UUID baseResumeVersionId, PackOptions options,
            List<PartView> parts, int version, Instant createdAt, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PackSummary(UUID id, PackStatus status, JobRef job, int version, Instant createdAt, Instant updatedAt) {
    }

    record PackList(List<PackSummary> items) {
    }
}
