package com.jobfinder.core.profile.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.activation.DigestSet;
import com.jobfinder.core.activation.IdentityDigester;
import com.jobfinder.core.activation.ResumeParsed;
import com.jobfinder.core.profile.ResumeVersionChanged;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only writer of parse results. Every write is a guarded, single-purpose update, so the
 * at-least-once delivery of parse messages (redelivery, two consumers racing) can never insert a
 * second version, trip {@code UNIQUE (resume_id, version_number)}, or overwrite a version that
 * already has content:
 *
 * <ul>
 * <li>parsing only ever <em>fills in</em> the upload version, and only while its
 * {@code structured} is still NULL;
 * <li>the resume moves PENDING to PARSED/FAILED exactly once;
 * <li>every method reports whether it changed anything, and "no" is a normal outcome.
 * </ul>
 *
 * Each call commits on its own transaction: it may run from an after-commit callback, where the
 * originating transaction can no longer take writes.
 */
@Component
class ResumeParseStore {

    /** The columns the worker needs; the file itself is fetched from object storage. */
    record Target(UUID resumeId, UUID userId, String fileKey, ParseStatus status) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    private final IdentityDigester digester;
    private final JsonMapper json;

    ResumeParseStore(JdbcClient jdbc, PlatformTransactionManager transactionManager,
            ApplicationEventPublisher events, IdentityDigester digester, JsonMapper json) {
        this.digester = digester;
        this.json = json;
        this.jdbc = jdbc;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    Optional<Target> findTarget(UUID resumeId) {
        return jdbc.sql("select id, user_id, file_key, parse_status from resumes where id = :id")
                .param("id", resumeId)
                .query((rs, row) -> new Target(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("file_key"), ParseStatus.valueOf(rs.getString("parse_status"))))
                .optional();
    }

    /**
     * Stores the parsed content in the given upload version and marks the resume PARSED. Returns
     * false, changing nothing, if the resume is gone, is no longer PENDING, or the version already
     * has content.
     */
    boolean complete(UUID resumeId, int versionNumber, String structuredJson, String warningsJson, String model,
            String promptVersion) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int resumes = jdbc.sql("""
                    update resumes set parse_status = 'PARSED', parse_error = null, updated_at = now()
                    where id = :id and parse_status = 'PENDING'
                    """).param("id", resumeId).update();
            if (resumes == 0) {
                return false;
            }
            int versions = jdbc.sql("""
                    update resume_versions
                    set structured = cast(:structured as jsonb), parse_warnings = cast(:warnings as jsonb),
                        model = :model, prompt_version = :promptVersion, updated_at = now()
                    where resume_id = :id and version_number = :versionNumber and source = 'UPLOAD'
                      and structured is null
                    """)
                    .param("structured", structuredJson)
                    .param("warnings", warningsJson)
                    .param("model", model)
                    .param("promptVersion", promptVersion)
                    .param("id", resumeId)
                    .param("versionNumber", versionNumber)
                    .update();
            if (versions == 0) {
                status.setRollbackOnly();
                return false;
            }
            // The version now has content: let the embeddings pipeline know once this commits.
            Optional<UUID> versionId = jdbc.sql(
                    "select id from resume_versions where resume_id = :id and version_number = :versionNumber")
                    .param("id", resumeId).param("versionNumber", versionNumber).query(UUID.class).optional();
            versionId.ifPresent(v -> events.publishEvent(new ResumeVersionChanged(v)));
            publishResumeParsed(resumeId, versionId.orElse(null), structuredJson);
            return true;
        }));
    }

    /**
     * Milestone M2 for the trial (docs/adr/0042): the digests of the file (its SHA-256 from the upload) and of the
     * contact email and phone numbers the parser found. Computed here, so no raw value leaves this module.
     */
    private void publishResumeParsed(UUID resumeId, UUID versionId, String structuredJson) {
        record Owner(UUID userId, byte[] sha256) {
        }
        Owner owner = jdbc.sql("select user_id, content_sha256 from resumes where id = :id").param("id", resumeId)
                .query((rs, n) -> new Owner(rs.getObject("user_id", UUID.class), rs.getBytes("content_sha256")))
                .single();
        String email = null;
        String phone = null;
        try {
            JsonNode contact = json.readTree(structuredJson).path("contact");
            email = contact.path("email").isString() ? contact.path("email").asString() : null;
            phone = contact.path("phone").isString() ? contact.path("phone").asString() : null;
        } catch (RuntimeException e) {
            // Stored content is already validated; an unreadable contact only means no CV signals.
        }
        List<DigestSet> emails = email == null ? List.of() : digester.email(email).map(List::of).orElse(List.of());
        List<DigestSet> phones = phone == null ? List.of() : digester.phones(phone);
        DigestSet file = owner.sha256() == null || owner.sha256().length != 32 ? null : digester.file(owner.sha256());
        events.publishEvent(new ResumeParsed(owner.userId(), versionId, file, emails, phones, Instant.now()));
    }

    /**
     * Puts a FAILED resume back to PENDING and queues its upload version for parsing again (once this commits).
     * Returns false, changing nothing, if the resume is gone or not FAILED with the given reason.
     */
    boolean retry(UUID resumeId, UUID userId, ParseFailureReason expectedReason) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int changed = jdbc.sql("""
                    update resumes set parse_status = 'PENDING', parse_error = null, updated_at = now()
                    where id = :id and parse_status = 'FAILED' and parse_error = :reason
                    """).param("id", resumeId).param("reason", expectedReason.code()).update();
            if (changed == 0) {
                return false;
            }
            events.publishEvent(new ResumeUploaded(resumeId, userId, 1));
            return true;
        }));
    }

    /** Marks a PENDING resume FAILED with a reason. Returns false if it was not PENDING (or is gone). */
    boolean fail(UUID resumeId, ParseFailureReason reason) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.sql("""
                update resumes set parse_status = 'FAILED', parse_error = :reason, updated_at = now()
                where id = :id and parse_status = 'PENDING'
                """).param("reason", reason.code()).param("id", resumeId).update() == 1));
    }
}
