package com.jobfinder.core.embeddings.internal;

import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.jobfinder.core.ingestion.JobContentChanged;
import com.jobfinder.core.profile.ResumeVersionChanged;

/**
 * Queues an embed request once the change that caused it has committed, so a worker can never look for a row that
 * does not exist yet. Only a <em>material</em> change is queued: the text that would be embedded now is hashed and
 * compared with what the stored vector was made from, so a refresh that changes nothing the vector depends on
 * (the common case for a re-fetched job) costs one cheap read and no provider call.
 *
 * <p><b>Consistency risk (ADR 0022).</b> This is an after-commit publish, not a transactional outbox. If the
 * process dies, or the broker is unreachable, between the commit and the publish, the row keeps a missing or stale
 * embedding and nothing queues it. That is logged, never thrown (the content change itself is already committed and
 * must not be reported as failed), and the backfill command finds exactly such rows and queues them.
 */
@Component
class EmbeddingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDispatcher.class);

    private final EmbeddingStore store;
    private final EmbeddingService service;
    private final EmbeddingPublisher publisher;
    private final EmbeddingProperties properties;

    EmbeddingDispatcher(EmbeddingStore store, EmbeddingService service, EmbeddingPublisher publisher,
            EmbeddingProperties properties) {
        this.store = store;
        this.service = service;
        this.publisher = publisher;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(JobContentChanged event) {
        queueIfStale(EmbeddingKind.JOB, event.jobId(),
                () -> store.job(event.jobId()).map(service::jobIsStale).orElse(false));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(ResumeVersionChanged event) {
        queueIfStale(EmbeddingKind.RESUME_VERSION, event.resumeVersionId(),
                () -> store.resumeVersion(event.resumeVersionId()).map(service::resumeVersionIsStale).orElse(false));
    }

    private void queueIfStale(EmbeddingKind kind, UUID id, Supplier<Boolean> isStale) {
        if (!properties.publishEnabled()) {
            return;
        }
        try {
            if (isStale.get()) {
                publisher.publish(kind, id);
            }
        } catch (RuntimeException e) {
            log.error("Could not queue the embedding of {} {}; the backfill will pick it up", kind, id, e);
        }
    }
}
