package com.jobfinder.core.applications.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationStatus;
import com.jobfinder.core.applications.internal.ApplicationDtos.CancelReason;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderKind;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderRequest;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderState;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderView;
import com.jobfinder.core.applications.internal.ApplicationStore.Row;
import com.jobfinder.core.notifications.RequestedMail;
import com.jobfinder.core.notifications.UserMail;
import com.jobfinder.core.shared.ApiException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reminders (docs/adr/0032-application-tracker.md): the user sets them, this sends them.
 *
 * <p>Sending is at most once per attempt and exactly once in practice: {@link ReminderStore#claimDue} claims a batch in
 * one statement (rows locked with SKIP LOCKED, an attempt counted, {@code claimed_at} set), so two instances running at
 * the same moment, a scheduler lock that expired early or a re-run all claim different rows or none. A reminder is then
 * sent through {@link UserMail}, which refuses users who switched email notifications off or cannot receive optional
 * mail (the reminder is cancelled with the reason), and marked SENT. If the mail server fails the claim stays, and the
 * reminder is tried again after {@code retryAfter}, up to {@code maxAttempts} times, then cancelled as SEND_FAILED. The
 * one window where an email can be sent twice is a process dying between the mail server accepting the message and the
 * row being marked SENT: the claim goes stale and the reminder is sent again (as in ADR 0028).
 *
 * <p>Nothing here ever logs an address, a note, a title or a company: they are personal data.
 */
@Service
class ReminderService {

    /** What became of a reminder the sender handled. */
    enum Outcome {
        SENT, CANCELLED, FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final int MAX_ROUNDS = 10;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final ReminderStore reminders;
    private final ApplicationStore applications;
    private final UserMail mail;
    private final ApplicationsProperties.Reminders properties;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    ReminderService(ReminderStore reminders, ApplicationStore applications, UserMail mail,
            ApplicationsProperties properties, TransactionTemplate tx, MeterRegistry meters, Clock clock) {
        this.reminders = reminders;
        this.applications = applications;
        this.mail = mail;
        this.properties = properties.reminders();
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    // --- the user's side ---

    ReminderView create(UUID userId, UUID applicationId, ReminderRequest request) {
        Instant now = Instant.now(clock);
        if (!request.dueAt().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reminder_in_past", "dueAt must be in the future.");
        }
        if (request.dueAt().isAfter(now.plus(properties.maxHorizon()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reminder_too_far",
                    "dueAt must be within " + properties.maxHorizon().toDays() + " days.");
        }
        UUID id = UUID.randomUUID();
        ReminderKind kind = request.kind() == null ? ReminderKind.FOLLOW_UP : request.kind();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        tx.executeWithoutResult(s -> {
            // The application row is locked so that two requests cannot both pass the limit.
            Row app = applications.findForUpdate(userId, applicationId).orElseThrow(ApplicationService::notFound);
            if (app.status().closed()) {
                throw new ApiException(HttpStatus.CONFLICT, "application_closed",
                        "A rejected or withdrawn application cannot get reminders.");
            }
            if (reminders.countPending(applicationId) >= properties.maxPendingPerApplication()) {
                throw new ApiException(HttpStatus.CONFLICT, "too_many_reminders", "An application can have up to "
                        + properties.maxPendingPerApplication() + " pending reminders.");
            }
            reminders.insert(id, applicationId, userId, kind, note, request.dueAt(), now);
        });
        return view(reminders.find(userId, applicationId, id).orElseThrow());
    }

    List<ReminderView> list(UUID userId, UUID applicationId) {
        applications.find(userId, applicationId).orElseThrow(ApplicationService::notFound);
        return reminders.list(userId, applicationId).stream().map(ReminderService::view).toList();
    }

    /**
     * Cancels a pending reminder. Cancelling one that is already cancelled does nothing; one that was sent cannot be
     * taken back (409 {@code reminder_already_sent}).
     */
    void cancel(UUID userId, UUID applicationId, UUID id) {
        applications.find(userId, applicationId).orElseThrow(ApplicationService::notFound);
        ReminderStore.Row row = reminders.find(userId, applicationId, id).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "reminder_not_found", "Reminder not found."));
        if (row.state() == ReminderState.SENT) {
            throw new ApiException(HttpStatus.CONFLICT, "reminder_already_sent",
                    "This reminder was already sent.");
        }
        reminders.cancel(id, CancelReason.USER, Instant.now(clock));
    }

    static ReminderView view(ReminderStore.Row r) {
        return new ReminderView(r.id(), r.applicationId(), r.kind(), r.note(), r.dueAt(), r.state(), r.sentAt(),
                r.cancelReason(), r.createdAt());
    }

    // --- the sender ---

    /**
     * Sends the reminders due at {@code now}, in batches of at most {@code batchSize} (up to {@value #MAX_ROUNDS}
     * batches per run, so one run cannot go on forever), and returns how many were sent.
     */
    int sendDue(Instant now) {
        int given = reminders.giveUp(now, properties.maxAttempts(), properties.retryAfter());
        if (given > 0) {
            meters.counter("applications.reminders", "outcome", "GAVE_UP").increment(given);
            log.warn("Gave up on {} reminder(s) after {} failed attempts", given, properties.maxAttempts());
        }
        int sent = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<ReminderStore.Row> claimed = reminders.claimDue(now, properties.maxAttempts(),
                    properties.retryAfter(), properties.batchSize());
            for (ReminderStore.Row reminder : claimed) {
                if (deliver(reminder, now) == Outcome.SENT) {
                    sent++;
                }
            }
            if (claimed.size() < properties.batchSize()) {
                break;
            }
        }
        return sent;
    }

    /** Sends one claimed reminder. Whatever goes wrong is returned, never thrown, so one failure stops no batch. */
    Outcome deliver(ReminderStore.Row reminder, Instant now) {
        try {
            Row app = applications.find(reminder.userId(), reminder.applicationId()).orElse(null);
            if (app == null) {
                // Deleted since it was claimed: its reminders went with it.
                return count(Outcome.CANCELLED, "APPLICATION_DELETED");
            }
            if (app.status().closed()) {
                reminders.cancel(reminder.id(), CancelReason.APPLICATION_CLOSED, now);
                return count(Outcome.CANCELLED, CancelReason.APPLICATION_CLOSED.name());
            }
            UserMail.Result result = mail.send(reminder.userId(), compose(reminder, app));
            switch (result) {
                case SENT -> {
                    reminders.markSent(reminder.id(), now);
                    return count(Outcome.SENT, "SENT");
                }
                case EMAIL_DISABLED -> {
                    reminders.cancel(reminder.id(), CancelReason.EMAIL_DISABLED, now);
                    return count(Outcome.CANCELLED, CancelReason.EMAIL_DISABLED.name());
                }
                default -> {
                    reminders.cancel(reminder.id(), CancelReason.NO_RECIPIENT, now);
                    return count(Outcome.CANCELLED, CancelReason.NO_RECIPIENT.name());
                }
            }
        } catch (RuntimeException e) {
            // Class name only: a mail server's message can hold the address.
            log.warn("Could not send reminder (attempt {}): {}", reminder.attempts(), e.getClass().getSimpleName());
            log.debug("Reminder failure detail", e);
            return count(Outcome.FAILED, "FAILED");
        }
    }

    private Outcome count(Outcome outcome, String label) {
        meters.counter("applications.reminders", "outcome", label).increment();
        return outcome;
    }

    /** The email: what the reminder is for, the application's own facts and the user's note. */
    static RequestedMail compose(ReminderStore.Row reminder, Row app) {
        String what = app.company() == null ? app.title() : app.title() + " at " + app.company();
        String subject = switch (reminder.kind()) {
            case FOLLOW_UP -> "Reminder: follow up on " + what;
            case INTERVIEW -> "Reminder: interview for " + what;
            case CUSTOM -> "Reminder: " + what;
        };
        List<String> lines = new ArrayList<>();
        lines.add(switch (reminder.kind()) {
            case FOLLOW_UP -> "You asked to be reminded to follow up on your application for " + what + ".";
            case INTERVIEW -> "You asked to be reminded about your interview for " + what + ".";
            case CUSTOM -> "You asked to be reminded about your application for " + what + ".";
        });
        if (reminder.note() != null) {
            lines.add("Your note: " + reminder.note());
        }
        StringBuilder status = new StringBuilder("Status: ").append(app.status().name());
        if (app.status() != ApplicationStatus.SAVED && app.appliedAt() != null) {
            status.append(", applied on ").append(DAY.format(app.appliedAt().atZone(ZoneOffset.UTC)));
        }
        lines.add(status.toString());
        lines.add("Reminder set for " + WHEN.format(reminder.dueAt().atZone(ZoneOffset.UTC)) + " UTC.");
        return new RequestedMail(subject, subject, lines, "Open your applications", "/applications");
    }
}
