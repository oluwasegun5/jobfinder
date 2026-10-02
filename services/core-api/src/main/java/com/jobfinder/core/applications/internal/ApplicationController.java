package com.jobfinder.core.applications.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationDetail;
import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationStatus;
import com.jobfinder.core.applications.internal.ApplicationDtos.CreateRequest;
import com.jobfinder.core.applications.internal.ApplicationDtos.FollowUpDraft;
import com.jobfinder.core.applications.internal.ApplicationDtos.FollowUpRequest;
import com.jobfinder.core.applications.internal.ApplicationDtos.ListResponse;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderList;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderRequest;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderView;
import com.jobfinder.core.applications.internal.ApplicationDtos.StatusRequest;
import com.jobfinder.core.applications.internal.ApplicationDtos.UpdateRequest;
import com.jobfinder.core.applications.internal.ApplicationService.Outcome;
import com.jobfinder.core.identity.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The application tracker (docs/adr/0032-application-tracker.md). Every endpoint needs a signed-in user and acts on that
 * user's applications only: someone else's application or reminder is a 404, never a 403.
 */
@RestController
class ApplicationController {

    private final ApplicationService applications;
    private final ReminderService reminders;
    private final FollowUpService followUps;

    ApplicationController(ApplicationService applications, ReminderService reminders, FollowUpService followUps) {
        this.applications = applications;
        this.reminders = reminders;
        this.followUps = followUps;
    }

    /**
     * Creates an application from a job ("I applied", "save to tracker") or by hand (a job found elsewhere): 201 when it
     * was created, 200 with the existing one when the caller already tracks this job (nothing is changed: move it with
     * {@code POST /applications/{id}/status}). The status defaults to {@code APPLIED}. 404 {@code job_not_found},
     * 400 {@code title_required} (by hand), {@code invalid_url}, {@code invalid_applied_at}, {@code invalid_pack},
     * {@code invalid_document}, {@code document_job_mismatch}, 409 {@code application_limit_reached}.
     */
    @PostMapping("/applications")
    ResponseEntity<ApplicationDetail> create(@RequestBody @Valid CreateRequest body) {
        UUID userId = CurrentUser.require().id();
        Outcome outcome = applications.create(userId, body);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(applications.detail(userId, outcome.row()));
    }

    /**
     * The caller's applications, newest status change first. {@code status} (repeatable) filters, {@code grouped=true}
     * returns them as a board (one list per status) instead of a flat list, {@code limit} (default 200, at most 500)
     * bounds the answer; {@code counts} always covers every status.
     */
    @GetMapping("/applications")
    ListResponse list(@RequestParam(required = false) List<ApplicationStatus> status,
            @RequestParam(defaultValue = "false") boolean grouped,
            @RequestParam(required = false) @Min(1) @Max(ApplicationService.MAX_LIMIT) Integer limit) {
        return applications.list(CurrentUser.require().id(), status, grouped, limit);
    }

    /** One application with its status history (oldest first) and its reminders (by due time). */
    @GetMapping("/applications/{id}")
    ApplicationDetail get(@PathVariable UUID id) {
        UUID userId = CurrentUser.require().id();
        return applications.detail(userId, applications.get(userId, id));
    }

    /** Changes notes, url and, for an entry made by hand, title and company (see {@link UpdateRequest}). */
    @PatchMapping("/applications/{id}")
    ApplicationDetail update(@PathVariable UUID id, @RequestBody @Valid UpdateRequest body) {
        UUID userId = CurrentUser.require().id();
        return applications.detail(userId, applications.update(userId, id, body));
    }

    /**
     * Moves the application to another status and records the event in its history. The status it already has is a
     * 200 no-op that records nothing; back to {@code SAVED} after it left is 409 {@code invalid_transition}; an unknown
     * status is a 400. {@code REJECTED} and {@code WITHDRAWN} cancel its pending reminders.
     */
    @PostMapping("/applications/{id}/status")
    ApplicationDetail changeStatus(@PathVariable UUID id, @RequestBody @Valid StatusRequest body) {
        UUID userId = CurrentUser.require().id();
        return applications.detail(userId, applications.changeStatus(userId, id, body));
    }

    /** Deletes the application with its history and reminders. */
    @DeleteMapping("/applications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        applications.delete(CurrentUser.require().id(), id);
    }

    /**
     * Adds a reminder: an email at {@code dueAt} (in the future, within a year). Nothing is added by default: the user
     * chooses. 409 {@code application_closed} for a rejected or withdrawn application, {@code too_many_reminders}.
     */
    @PostMapping("/applications/{id}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    ReminderView addReminder(@PathVariable UUID id, @RequestBody @Valid ReminderRequest body) {
        return reminders.create(CurrentUser.require().id(), id, body);
    }

    /** All reminders of the application, whatever their state, by due time. */
    @GetMapping("/applications/{id}/reminders")
    ReminderList listReminders(@PathVariable UUID id) {
        return new ReminderList(reminders.list(CurrentUser.require().id(), id));
    }

    /** Cancels a pending reminder (it stays in the list as CANCELLED); one already sent is 409. */
    @DeleteMapping("/applications/{id}/reminders/{reminderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelReminder(@PathVariable UUID id, @PathVariable UUID reminderId) {
        reminders.cancel(CurrentUser.require().id(), id, reminderId);
    }

    /**
     * Writes a follow-up email for the application from the caller's primary resume and the application's facts, and
     * returns it as a draft: nothing is stored and nothing is sent. Metered like the writing endpoints. 409
     * {@code resume_required}, {@code not_applied_yet} (still SAVED), 429 {@code ai_daily_cap_reached}, 503
     * {@code follow_up_unavailable} (ai-service down or answering badly) or {@code follow_up_rejected} (the draft
     * claimed something the resume does not show and was discarded).
     */
    @PostMapping("/applications/{id}/follow-up-draft")
    FollowUpDraft followUpDraft(@PathVariable UUID id, @RequestBody(required = false) @Valid FollowUpRequest body) {
        return followUps.draft(CurrentUser.require().id(), id, body == null ? FollowUpRequest.defaults() : body);
    }
}
