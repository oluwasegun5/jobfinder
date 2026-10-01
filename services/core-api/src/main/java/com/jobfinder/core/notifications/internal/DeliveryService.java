package com.jobfinder.core.notifications.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jobfinder.core.identity.MailRecipient;
import com.jobfinder.core.notifications.internal.MailComposer.Mail;
import com.jobfinder.core.notifications.internal.NotificationLog.Claim;
import com.jobfinder.core.notifications.internal.NotificationLog.Kind;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sends one email exactly once per window (docs/adr/0028-notifications.md): claim the window in the log, build the
 * content, send, record. Whatever goes wrong (the content cannot be built, the mail server refuses) is recorded and
 * returned as {@link Outcome#FAILED}; it is never thrown, so one user's failure cannot stop a batch.
 *
 * <p>Delivery is at-least-once only in one narrow case: if the process dies between the mail server accepting the message
 * and the log row being marked sent, the stale claim is taken over later and the email is sent again. Everything else is
 * once.
 */
@Service
class DeliveryService {

    /** What happened to a window. */
    enum Outcome {
        /** The email was sent. */
        SENT,
        /** There was nothing to send; the window is closed. */
        SKIPPED,
        /** Building or sending failed; it will be tried again if attempts and time are left. */
        FAILED,
        /** Someone else sent, skipped or is sending this window, or it is out of attempts or not due yet. */
        NOT_CLAIMED
    }

    /** What a builder produced: the email, and the jobs it lists (recorded, so they are never listed again). */
    record Built(List<UUID> jobIds, Mail mail) {
    }

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final NotificationLog notificationLog;
    private final NotificationMailer mailer;
    private final NotificationProperties properties;
    private final MeterRegistry meters;

    DeliveryService(NotificationLog notificationLog, NotificationMailer mailer, NotificationProperties properties,
            MeterRegistry meters) {
        this.notificationLog = notificationLog;
        this.mailer = mailer;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * @param scope    the saved search id for a search email, else the empty string
     * @param builder  makes the email, or nothing when there is nothing new to say (then no email is sent at all)
     */
    Outcome deliver(MailRecipient to, Kind kind, UUID savedSearchId, String windowKey, Instant now,
            Supplier<Optional<Built>> builder) {
        NotificationProperties.Delivery config = properties.delivery();
        String scope = savedSearchId == null ? "" : savedSearchId.toString();
        Optional<Claim> claim = notificationLog.claim(to.userId(), kind, scope, savedSearchId, windowKey, now,
                config.maxAttempts(), config.staleAfter());
        if (claim.isEmpty()) {
            return count(kind, Outcome.NOT_CLAIMED);
        }
        Claim mine = claim.get();
        try {
            Optional<Built> built = builder.get();
            if (built.isEmpty()) {
                notificationLog.skipped(mine.id(), now);
                return count(kind, Outcome.SKIPPED);
            }
            mailer.send(to.email(), built.get().mail());
            notificationLog.sent(mine.id(), built.get().jobIds(), now);
            return count(kind, Outcome.SENT);
        } catch (RuntimeException e) {
            // Class name only: a mail server's message can hold the address.
            log.warn("Could not send a {} for user {} (attempt {}): {}", kind, to.userId(), mine.attempts(),
                    e.getClass().getSimpleName());
            log.debug("Delivery failure detail", e);
            try {
                Duration wait = config.initialBackoff().multipliedBy(1L << Math.min(mine.attempts() - 1, 10));
                notificationLog.failed(mine.id(), e.getClass().getSimpleName(), now, now.plus(wait));
            } catch (RuntimeException inner) {
                log.error("Could not record a failed {} for user {}", kind, to.userId(), inner);
            }
            return count(kind, Outcome.FAILED);
        }
    }

    private Outcome count(Kind kind, Outcome outcome) {
        meters.counter("notifications.emails", "kind", kind.name(), "outcome", outcome.name()).increment();
        return outcome;
    }
}
