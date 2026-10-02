package com.jobfinder.core.applications.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes of the application tracker (docs/adr/0032-application-tracker.md). */
final class ApplicationDtos {

    private ApplicationDtos() {
    }

    /** The columns of the board, in the order they are shown. */
    enum ApplicationStatus {
        /** Tracked, not applied yet. */
        SAVED,
        APPLIED,
        SCREENING,
        INTERVIEW,
        OFFER,
        REJECTED,
        WITHDRAWN;

        /** REJECTED and WITHDRAWN end an application: its pending reminders are cancelled and none can be added. */
        boolean closed() {
            return this == REJECTED || this == WITHDRAWN;
        }

        /** The statuses that say the user did apply. */
        boolean applied() {
            return this == APPLIED || this == SCREENING || this == INTERVIEW || this == OFFER;
        }
    }

    enum ReminderKind {
        FOLLOW_UP, INTERVIEW, CUSTOM
    }

    enum ReminderState {
        PENDING, SENT, CANCELLED
    }

    /** Why a reminder was CANCELLED. */
    enum CancelReason {
        /** The user deleted it. */
        USER,
        /** The application became REJECTED or WITHDRAWN. */
        APPLICATION_CLOSED,
        /** The user has email notifications switched off. */
        EMAIL_DISABLED,
        /** The account cannot receive optional mail (its address is not verified, or it is disabled). */
        NO_RECIPIENT,
        /** Sending failed on every attempt. */
        SEND_FAILED
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

    // --- requests ---

    /**
     * Creates an application, from a job or by hand.
     *
     * <p>From a job: give {@code jobId}; the title and company are copied from the job (any {@code title} or
     * {@code company} sent is ignored). By hand: no {@code jobId}, and {@code title} is required. {@code url} is where the
     * posting or the application lives (http or https). {@code status} is where the application starts and defaults to
     * {@code APPLIED}; {@code appliedAt} (not in the future) defaults to now for a status that means "applied" and must
     * be absent for {@code SAVED}. {@code packId} and the document ids say what was used: they must belong to the caller,
     * the documents must be approved and of the matching type, and for an application from a job they must have been
     * made for that job.
     */
    record CreateRequest(UUID jobId, @Size(max = 400) String title, @Size(max = 400) String company,
            @Size(max = 2000) String url, ApplicationStatus status, Instant appliedAt, @Size(max = 10000) String notes,
            UUID packId, UUID resumeDocumentId, UUID coverLetterDocumentId, UUID screeningAnswersDocumentId) {
    }

    /**
     * Changes an application; a field left out (null) is unchanged and an empty string clears {@code company},
     * {@code url} and {@code notes}. {@code title} and {@code company} belong to the job of an application made from a
     * job and cannot be changed there (409 {@code job_fields_fixed}); {@code appliedAt} (not in the future) cannot be set
     * on an application that is still SAVED.
     */
    record UpdateRequest(@Size(max = 400) String title, @Size(max = 400) String company, @Size(max = 2000) String url,
            @Size(max = 10000) String notes, Instant appliedAt) {
    }

    record StatusRequest(@NotNull ApplicationStatus status, @Size(max = 1000) String note) {
    }

    /** {@code dueAt} must be in the future and within a year; {@code kind} defaults to FOLLOW_UP. */
    record ReminderRequest(@NotNull Instant dueAt, ReminderKind kind, @Size(max = 500) String note) {
    }

    /** How the follow-up email is written; every field is optional ({@code FORMAL} and {@code SHORT} by default). */
    record FollowUpRequest(Tone tone, Length length, @Size(max = 1000) String notes) {

        FollowUpRequest {
            tone = tone == null ? Tone.FORMAL : tone;
            length = length == null ? Length.SHORT : length;
            notes = notes == null || notes.isBlank() ? null : notes.strip();
        }

        static FollowUpRequest defaults() {
            return new FollowUpRequest(null, null, null);
        }
    }

    // --- responses ---

    /**
     * An application as the board shows it. {@code nextReminderAt} is the earliest pending reminder, if any;
     * {@code statusChangedAt} orders the board.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApplicationView(UUID id, UUID jobId, String title, String company, String url, ApplicationStatus status,
            String notes, Instant appliedAt, UUID packId, UUID resumeDocumentId, UUID coverLetterDocumentId,
            UUID screeningAnswersDocumentId, Instant statusChangedAt, Instant nextReminderAt, Instant createdAt,
            Instant updatedAt) {
    }

    /** One entry of the status history; {@code from} is null for the event that created the application. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EventView(UUID id, ApplicationStatus from, ApplicationStatus to, String note, Instant at) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReminderView(UUID id, UUID applicationId, ReminderKind kind, String note, Instant dueAt,
            ReminderState state, Instant sentAt, CancelReason cancelReason, Instant createdAt) {
    }

    /** An application with its status history (oldest first) and all its reminders (by due time). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApplicationDetail(UUID id, UUID jobId, String title, String company, String url,
            ApplicationStatus status, String notes, Instant appliedAt, UUID packId, UUID resumeDocumentId,
            UUID coverLetterDocumentId, UUID screeningAnswersDocumentId, Instant statusChangedAt,
            Instant nextReminderAt, Instant createdAt, Instant updatedAt, List<EventView> events,
            List<ReminderView> reminders) {

        static ApplicationDetail of(ApplicationView a, List<EventView> events, List<ReminderView> reminders) {
            return new ApplicationDetail(a.id(), a.jobId(), a.title(), a.company(), a.url(), a.status(), a.notes(),
                    a.appliedAt(), a.packId(), a.resumeDocumentId(), a.coverLetterDocumentId(),
                    a.screeningAnswersDocumentId(), a.statusChangedAt(), a.nextReminderAt(), a.createdAt(),
                    a.updatedAt(), events, reminders);
        }
    }

    /**
     * The caller's applications. {@code counts} has every status (0 when empty) and counts all of the caller's
     * applications whatever the filter. Flat (default): {@code items}, newest status change first, filtered by
     * {@code status}. With {@code grouped=true}: {@code board}, every status as a key (or only the filtered ones) with
     * its applications, and no {@code items}. {@code truncated} says more than {@code limit} matched.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApplicationListResponse(List<ApplicationView> items, Map<ApplicationStatus, List<ApplicationView>> board,
            Map<ApplicationStatus, Integer> counts, boolean truncated) {
    }

    record ReminderList(List<ReminderView> items) {
    }

    /**
     * A draft only, never sent: the subject and the body of the email, plain text. {@code promptVersion} and
     * {@code model} are what made it.
     */
    record FollowUpDraft(String subject, String body, Tone tone, Length length, String model,
            String promptVersion) {
    }
}
