package com.jobfinder.core.ingestion.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.ingestion.FetchTarget;
import com.jobfinder.core.ingestion.IngestionRunStatus;
import com.jobfinder.core.ingestion.IngestionRunSummary;
import com.jobfinder.core.ingestion.JobSourceAdapter;
import com.jobfinder.core.ingestion.RawPosting;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * One run of one source: fetch each enabled target through the source's fault-tolerance layers,
 * store what came back as raw postings, and record the outcome. Must be called while holding the
 * source's lock (see {@link IngestionRunner}).
 *
 * <p>
 * A target that fails does not stop the run: it is counted as an error, the others carry on, and
 * the run ends PARTIAL (some failed) or FAILED (all failed). Each target's postings are stored in
 * one transaction, so a target is either stored whole or not at all.
 */
@Component
class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private static final int MAX_ERRORS_IN_SUMMARY = 5;
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final SourceStore sources;
    private final IngestionRunStore runs;
    private final RawPostingStore rawPostings;
    private final SourceResilienceProvider resilienceProvider;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    IngestionPipeline(SourceStore sources, IngestionRunStore runs, RawPostingStore rawPostings,
            SourceResilienceProvider resilienceProvider, MeterRegistry meters,
            PlatformTransactionManager transactionManager) {
        this.sources = sources;
        this.runs = runs;
        this.rawPostings = rawPostings;
        this.resilienceProvider = resilienceProvider;
        this.meters = meters;
        this.tx = new TransactionTemplate(transactionManager);
    }

    IngestionRunSummary execute(SourceStore.SourceRow source, JobSourceAdapter adapter) {
        Instant started = Instant.now();
        int interrupted = runs.closeInterrupted(source.id(), started);
        if (interrupted > 0) {
            log.warn("Source {}: closed {} run(s) left RUNNING by an instance that stopped", source.code(),
                    interrupted);
        }
        Instant since = runs.lastSuccessfulStart(source.id()).orElse(null);
        UUID runId = runs.start(source.id(), started);
        List<SourceStore.TargetRow> targets = sources.enabledTargets(source.id());
        SourceResilience resilience = resilienceProvider.forSource(source, sources.defaults());
        log.info("Source {}: run {} started, {} target(s), since {}", source.code(), runId, targets.size(), since);

        int fetched = 0;
        int created = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (SourceStore.TargetRow target : targets) {
            try {
                FetchTarget fetchTarget = new FetchTarget(target.id(), target.identifier(), target.companyId());
                List<RawPosting> postings = resilience.call(() -> fetchAll(adapter, fetchTarget, since));
                int[] stored = store(source, target, postings);
                fetched += postings.size();
                created += stored[0];
                updated += stored[1];
            } catch (RuntimeException e) {
                errors.add(target.identifier() + ": " + describe(e));
                log.warn("Source {}: target {} failed: {}", source.code(), target.identifier(), describe(e));
            }
        }

        IngestionRunStatus status = statusOf(targets.size(), errors.size());
        SourceHealth health = healthOf(targets.size(), errors.size());
        Instant finished = Instant.now();
        IngestionRunStore.Counts counts = new IngestionRunStore.Counts(fetched, created, updated, 0, errors.size());
        runs.finish(runId, status, counts, summarize(errors), finished);
        sources.recordRun(source.id(), finished, health);
        record(source.code(), status, counts, Duration.between(started, finished));
        log.info("Source {}: run {} {} (fetched {}, created {}, updated {}, errors {})", source.code(), runId, status,
                fetched, created, updated, errors.size());
        return new IngestionRunSummary(runId, source.code(), status, fetched, created, updated, 0, errors.size());
    }

    private static List<RawPosting> fetchAll(JobSourceAdapter adapter, FetchTarget target, Instant since) {
        try (Stream<RawPosting> stream = adapter.fetch(target, since)) {
            return stream.toList();
        }
    }

    /** Stores one target's postings in one transaction; returns {created, updated}. */
    private int[] store(SourceStore.SourceRow source, SourceStore.TargetRow target, List<RawPosting> postings) {
        Instant fetchedAt = Instant.now();
        return tx.execute(status -> {
            int created = 0;
            int updated = 0;
            for (RawPosting posting : postings) {
                if (rawPostings.upsert(source.id(), target.id(), posting, fetchedAt)) {
                    created++;
                } else {
                    updated++;
                }
            }
            return new int[] { created, updated };
        });
    }

    private static IngestionRunStatus statusOf(int targets, int errors) {
        if (errors == 0) {
            return IngestionRunStatus.SUCCEEDED;
        }
        return errors >= targets ? IngestionRunStatus.FAILED : IngestionRunStatus.PARTIAL;
    }

    private static SourceHealth healthOf(int targets, int errors) {
        if (targets == 0) {
            return SourceHealth.UNKNOWN;
        }
        if (errors == 0) {
            return SourceHealth.HEALTHY;
        }
        return errors >= targets ? SourceHealth.FAILING : SourceHealth.DEGRADED;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage() == null ? "" : ": " + e.getMessage();
        String text = e.getClass().getSimpleName() + message;
        return text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) + "..." : text;
    }

    private static String summarize(List<String> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        String joined = String.join("\n", errors.subList(0, Math.min(errors.size(), MAX_ERRORS_IN_SUMMARY)));
        return errors.size() > MAX_ERRORS_IN_SUMMARY
                ? joined + "\n... and " + (errors.size() - MAX_ERRORS_IN_SUMMARY) + " more"
                : joined;
    }

    private void record(String sourceCode, IngestionRunStatus status, IngestionRunStore.Counts counts,
            Duration duration) {
        meters.counter("ingestion.runs", "source", sourceCode, "status", status.name()).increment();
        meters.counter("ingestion.postings", "source", sourceCode, "outcome", "fetched").increment(counts.fetched());
        meters.counter("ingestion.postings", "source", sourceCode, "outcome", "created").increment(counts.created());
        meters.counter("ingestion.postings", "source", sourceCode, "outcome", "updated").increment(counts.updated());
        meters.counter("ingestion.target.errors", "source", sourceCode).increment(counts.errors());
        Timer.builder("ingestion.run.duration").tag("source", sourceCode).register(meters).record(duration);
    }
}
