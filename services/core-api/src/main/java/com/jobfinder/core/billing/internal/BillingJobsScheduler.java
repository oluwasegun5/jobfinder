package com.jobfinder.core.billing.internal;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * The clocks of billing: the monthly free grant and the subscription expiry, each under a ShedLock lock so one
 * instance runs at a time. The lock is an optimisation, not what makes the work safe (both jobs are idempotent).
 * Switched off with {@code app.billing.jobs.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "app.billing.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class BillingJobsScheduler {

    static final String FREE_GRANT_LOCK = "billing:free-grant";
    static final String EXPIRY_LOCK = "billing:expiry";

    private static final Logger log = LoggerFactory.getLogger(BillingJobsScheduler.class);

    private final BillingJobs jobs;
    private final LockingTaskExecutor locks;
    private final BillingProperties properties;
    private final Clock clock;

    BillingJobsScheduler(BillingJobs jobs, LockingTaskExecutor locks, BillingProperties properties, Clock clock) {
        this.jobs = jobs;
        this.locks = locks;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.billing.jobs.free-grant-cron:0 10 0 * * *}", zone = "${app.billing.jobs.zone:UTC}")
    void scheduledFreeGrant() {
        locked(FREE_GRANT_LOCK, () -> jobs.grantFree(Instant.now(clock)));
    }

    @Scheduled(cron = "${app.billing.jobs.expiry-cron:0 0 * * * *}", zone = "${app.billing.jobs.zone:UTC}")
    void scheduledExpiry() {
        locked(EXPIRY_LOCK, () -> jobs.expire(Instant.now(clock)));
    }

    private void locked(String name, java.util.function.IntSupplier work) {
        LockConfiguration lock = new LockConfiguration(Instant.now(), name, properties.jobs().lockAtMostFor(),
                java.time.Duration.ofSeconds(30));
        try {
            LockingTaskExecutor.TaskWithResult<Integer> task = work::getAsInt;
            locks.executeWithLock(task, lock);
        } catch (Throwable e) {
            log.error("The {} job could not run", name, e);
        }
    }
}
