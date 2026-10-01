package com.jobfinder.core.embeddings.internal;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jobfinder.core.embeddings.internal.EmbeddingDtos.BackfillResponse;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.Counts;
import com.jobfinder.core.embeddings.internal.EmbeddingStore.JobRow;
import com.jobfinder.core.embeddings.internal.EmbeddingStore.ResumeVersionRow;

/**
 * Queues every active job and every resume version with content whose embedding is missing or stale (no vector,
 * another model than the pinned one, or text that no longer hashes to what was embedded). Walks each table in id
 * order, one page at a time, so memory stays flat however many rows there are. Safe to run at any time and to run
 * twice: ai-service skips rows that are current by the time it gets to them.
 */
@Component
class EmbeddingBackfill {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfill.class);
    private static final UUID LOWEST = new UUID(0L, 0L);

    private final EmbeddingStore store;
    private final EmbeddingService service;
    private final EmbeddingPublisher publisher;
    private final EmbeddingProperties properties;

    EmbeddingBackfill(EmbeddingStore store, EmbeddingService service, EmbeddingPublisher publisher,
            EmbeddingProperties properties) {
        this.store = store;
        this.service = service;
        this.publisher = publisher;
        this.properties = properties;
    }

    BackfillResponse run(boolean jobs, boolean resumeVersions) {
        Counts jobCounts = jobs ? walk(EmbeddingKind.JOB,
                after -> store.jobCandidates(after, properties.model(), properties.backfillPageSize()),
                JobRow::id, service::jobIsStale) : new Counts(0, 0);
        Counts resumeCounts = resumeVersions ? walk(EmbeddingKind.RESUME_VERSION,
                after -> store.resumeVersionCandidates(after, properties.model(), properties.backfillPageSize()),
                ResumeVersionRow::id, service::resumeVersionIsStale) : new Counts(0, 0);
        log.info("Embedding backfill queued {} jobs and {} resume versions (model {})", jobCounts.enqueued(),
                resumeCounts.enqueued(), properties.model());
        return new BackfillResponse(properties.model(), jobCounts, resumeCounts);
    }

    private <R> Counts walk(EmbeddingKind kind, Function<UUID, List<R>> page, Function<R, UUID> id,
            Predicate<R> stale) {
        int scanned = 0;
        int enqueued = 0;
        UUID after = LOWEST;
        while (true) {
            List<R> rows = page.apply(after);
            for (R row : rows) {
                scanned++;
                if (stale.test(row)) {
                    publisher.publish(kind, id.apply(row));
                    enqueued++;
                }
            }
            if (rows.size() < properties.backfillPageSize()) {
                return new Counts(scanned, enqueued);
            }
            after = id.apply(rows.get(rows.size() - 1));
        }
    }
}
