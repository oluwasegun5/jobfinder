package com.jobfinder.core.profile.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.profile.ResumeVersionChanged;

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

    ResumeParseStore(JdbcClient jdbc, PlatformTransactionManager transactionManager,
            ApplicationEventPublisher events) {
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
            jdbc.sql("select id from resume_versions where resume_id = :id and version_number = :versionNumber")
                    .param("id", resumeId).param("versionNumber", versionNumber).query(java.util.UUID.class)
                    .optional().ifPresent(versionId -> events.publishEvent(new ResumeVersionChanged(versionId)));
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
