package com.jobfinder.core.matching.internal;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * Starts the nightly matching run on a cron schedule ({@code app.matching.batch.cron}, in
 * {@code app.matching.batch.zone}; off with {@code app.matching.batch.enabled=false}). A ShedLock lock named
 * {@code matching:nightly} (the table and provider ingestion uses) means one instance runs it at a time, however many
 * are deployed.
 */
@Component
@ConditionalOnProperty(prefix = "app.matching.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
class MatchBatchScheduler {

    static final String LOCK_NAME = "matching:nightly";

    private static final Logger log = LoggerFactory.getLogger(MatchBatchScheduler.class);

    private final MatchBatchService batch;
    private final LockingTaskExecutor lockExecutor;
    private final MatchingProperties properties;

    MatchBatchScheduler(MatchBatchService batch, LockingTaskExecutor lockExecutor, MatchingProperties properties) {
        this.batch = batch;
        this.lockExecutor = lockExecutor;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.matching.batch.cron:0 30 2 * * *}", zone = "${app.matching.batch.zone:UTC}")
    void nightly() {
        run();
    }

    /** Runs the batch under the lock; returns false when another instance holds it. */
    boolean run() {
        LockConfiguration lock = new LockConfiguration(Instant.now(), LOCK_NAME, properties.batch().lockAtMostFor(),
                java.time.Duration.ofMinutes(1));
        try {
            return lockExecutor.executeWithLock(batch::runOnce, lock).wasExecuted();
        } catch (RuntimeException | Error e) {
            log.error("Nightly matching could not run", e);
            return false;
        } catch (Throwable e) {
            log.error("Nightly matching could not run", e);
            return false;
        }
    }
}
