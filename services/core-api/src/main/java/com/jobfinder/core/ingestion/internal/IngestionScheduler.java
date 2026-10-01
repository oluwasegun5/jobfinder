package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Wakes up every {@code poll-interval-ms}, finds the enabled sources that are due and starts each
 * on its own thread, so a slow source never delays the others. If an instance starts a source that
 * another is already running, the lock turns it away. Disabling a source (or setting
 * {@code app.ingestion.scheduler.enabled=false}) stops it being scheduled; it does not interrupt a
 * run already in progress.
 */
@Component
@ConditionalOnProperty(prefix = "app.ingestion.scheduler", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final SourceStore sources;
    private final IngestionRunner runner;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    IngestionScheduler(SourceStore sources, IngestionRunner runner) {
        this.sources = sources;
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${app.ingestion.scheduler.poll-interval-ms:60000}",
            initialDelayString = "${app.ingestion.scheduler.initial-delay-ms:60000}")
    void tick() {
        try {
            tick(Instant.now());
        } catch (RuntimeException e) {
            log.error("Ingestion scheduler tick failed", e);
        }
    }

    /** Starts every due source; returns the started runs (tests wait on them). */
    List<Future<?>> tick(Instant now) {
        List<Future<?>> started = new ArrayList<>();
        for (SourceStore.SourceRow source : sources.findEnabled()) {
            if (runner.adapters().containsKey(source.code())
                    && source.settings().isDue(source.code(), source.lastRunAt(), now)) {
                started.add(executor.submit(() -> runQuietly(source.code())));
            }
        }
        return started;
    }

    private void runQuietly(String sourceCode) {
        try {
            runner.runNow(sourceCode);
        } catch (RuntimeException e) {
            log.error("Scheduled run of source {} failed", sourceCode, e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }
}
