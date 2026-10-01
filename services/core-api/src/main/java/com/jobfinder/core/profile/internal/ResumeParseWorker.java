package com.jobfinder.core.profile.internal;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.billing.AiDailyCapReachedException;
import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.profile.internal.AiServiceResumeParser.Parsed;
import com.jobfinder.core.profile.internal.ResumeParseStore.Target;
import com.jobfinder.core.storage.ObjectNotFoundException;
import com.jobfinder.core.storage.ObjectStorage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes parse requests: fetch the CV from storage, have ai-service parse it, store the result.
 *
 * <p><b>Idempotent by construction.</b> RabbitMQ delivers at least once, so the same message can
 * arrive again after a crash before the ack, a broker restart, or a consumer timeout. Handling it
 * twice is harmless because nothing here ever <em>inserts</em> (the upload already created
 * version 1) and the {@link ResumeParseStore} writes only fill an empty version of a PENDING
 * resume. A redelivery therefore ends as one of:
 * <ul>
 * <li>skipped up front, before any LLM cost: the resume is already PARSED or FAILED, or is gone;
 * <li>a lost race at write time: another delivery finished first, the write matches no row, and
 * nothing changes. Later versions (user edits) are never touched.
 * </ul>
 *
 * <p><b>Daily cap.</b> Before every call to ai-service the user's daily AI allowance is checked
 * ({@link AiUsageGate}); a user who has used it up gets the resume marked FAILED with the reason
 * {@code ai_daily_cap_reached} and no call is made. The user can ask for the parse again after the reset
 * ({@code POST /resumes/{id}/reparse}).
 *
 * <p>Every outcome ends in an ack: permanent failures set FAILED with a reason, transient ones are
 * retried with backoff and then set FAILED. Only a malformed message is rejected (to the DLQ).
 */
@Component
class ResumeParseWorker {

    private static final Logger log = LoggerFactory.getLogger(ResumeParseWorker.class);

    private final ResumeParseStore store;
    private final ObjectStorage storage;
    private final AiServiceResumeParser parser;
    private final ResumeParsingProperties properties;
    private final JsonMapper json;
    private final AiUsageGate gate;

    ResumeParseWorker(ResumeParseStore store, ObjectStorage storage, AiServiceResumeParser parser,
            ResumeParsingProperties properties, JsonMapper json, AiUsageGate gate) {
        this.gate = gate;
        this.store = store;
        this.storage = storage;
        this.parser = parser;
        this.properties = properties;
        this.json = json;
    }

    @RabbitListener(queues = "${app.resumes.parsing.queue:resumes.parse}")
    void receive(String body) {
        ResumeParseMessage message;
        try {
            message = json.readValue(body, ResumeParseMessage.class);
        } catch (JacksonException e) {
            throw new AmqpRejectAndDontRequeueException("Malformed resume parse message", e);
        }
        if (message.resumeId() == null || message.userId() == null || message.versionNumber() < 1) {
            throw new AmqpRejectAndDontRequeueException("Incomplete resume parse message");
        }
        handle(message);
    }

    void handle(ResumeParseMessage message) {
        Optional<Target> found = store.findTarget(message.resumeId());
        if (found.isEmpty()) {
            log.info("Skipping parse of resume {}: it no longer exists", message.resumeId());
            return;
        }
        Target target = found.get();
        if (!target.userId().equals(message.userId())) {
            log.warn("Skipping parse of resume {}: the message names a different owner", message.resumeId());
            return;
        }
        if (target.status() != ParseStatus.PENDING) {
            log.info("Skipping parse of resume {}: already {}", message.resumeId(), target.status());
            return;
        }

        try {
            Parsed parsed = parseWithRetries(target);
            boolean stored = store.complete(target.resumeId(), message.versionNumber(), parsed.structuredJson(),
                    parsed.warningsJson(), parsed.model(), parsed.promptVersion());
            log.info(stored ? "Parsed resume {}" : "Parsed resume {} but another delivery got there first",
                    target.resumeId());
        } catch (ImmediateRequeueAmqpException e) {
            throw e;
        } catch (AiDailyCapReachedException e) {
            log.info("Parsing resume {} blocked: the daily AI cap is reached (resets at {})", target.resumeId(),
                    e.resetsAt());
            store.fail(target.resumeId(), ParseFailureReason.AI_DAILY_CAP_REACHED);
        } catch (ParseFailure e) {
            log.warn("Parsing resume {} failed: {} ({})", target.resumeId(), e.reason().code(), e.getMessage());
            store.fail(target.resumeId(), e.reason());
        } catch (RuntimeException e) {
            // A bug must not become an endless redelivery loop.
            log.error("Unexpected error parsing resume {}", target.resumeId(), e);
            store.fail(target.resumeId(), ParseFailureReason.UNEXPECTED_ERROR);
        }
    }

    private Parsed parseWithRetries(Target target) {
        Duration backoff = properties.initialBackoff();
        for (int attempt = 1;; attempt++) {
            try {
                // Checked before every attempt, so a retry after a transient failure cannot cross the cap.
                gate.requireAllowance(target.userId(), "parse_resume");
                return parser.parse(target.userId(), read(target));
            } catch (ParseFailure e) {
                if (!e.retryable() || attempt >= properties.maxAttempts()) {
                    throw e;
                }
                log.info("Parsing resume {} failed transiently (attempt {}/{}); retrying in {}", target.resumeId(),
                        attempt, properties.maxAttempts(), backoff);
                pause(backoff);
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    private byte[] read(Target target) {
        try {
            return storage.get(target.fileKey());
        } catch (ObjectNotFoundException e) {
            throw ParseFailure.permanent(ParseFailureReason.FILE_MISSING, "the stored file no longer exists");
        } catch (RuntimeException e) {
            throw ParseFailure.transientFailure("object storage unavailable: " + e.getClass().getSimpleName(), e);
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            // Shutting down: give the message back so the next consumer starts it afresh.
            Thread.currentThread().interrupt();
            throw new ImmediateRequeueAmqpException("interrupted while waiting to retry a parse", e);
        }
    }
}
