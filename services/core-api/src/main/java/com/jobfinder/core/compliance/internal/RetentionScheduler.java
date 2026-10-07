package com.jobfinder.core.compliance.internal;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * The clock of the retention jobs: once a day (app.retention.run-interval) under a ShedLock lock, so one instance runs
 * them however many are deployed. Switched off with {@code app.retention.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
class RetentionScheduler {

    static final String LOCK_NAME = "compliance:retention";

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionRunner runner;
    private final LockingTaskExecutor locks;

    RetentionScheduler(RetentionRunner runner, LockingTaskExecutor locks) {
        this.runner = runner;
        this.locks = locks;
    }

    @Scheduled(fixedDelayString = "${app.retention.run-interval:24h}",
            initialDelayString = "${app.retention.initial-delay:10m}")
    void poll() {
        run(Instant.now());
    }

    /** Runs the tasks under the lock; false when another instance holds it (or the run could not start). */
    boolean run(Instant now) {
        LockConfiguration lock = new LockConfiguration(Instant.now(), LOCK_NAME, Duration.ofHours(1),
                Duration.ofMinutes(1));
        try {
            LockingTaskExecutor.TaskWithResult<Object> task = () -> runner.runAll(now);
            return locks.executeWithLock(task, lock).wasExecuted();
        } catch (Throwable e) {
            log.error("The {} job could not run", LOCK_NAME, e);
            return false;
        }
    }
}
