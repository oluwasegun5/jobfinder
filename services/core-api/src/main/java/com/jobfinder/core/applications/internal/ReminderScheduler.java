package com.jobfinder.core.applications.internal;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * The clock of the reminders (docs/adr/0032-application-tracker.md): every few minutes it sends the reminders that are
 * due, under a ShedLock lock (the table the other schedulers use), so one instance runs it at a time however many are
 * deployed. The lock is an optimisation, not what makes sending exactly once: that is the claim in
 * {@link ReminderStore#claimDue}, which holds even if two instances run at once. A run that throws is logged and the
 * next one starts normally. Switched off with {@code app.applications.reminders.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.applications.reminders", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class ReminderScheduler {

    static final String LOCK_NAME = "applications:reminders";

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminders;
    private final LockingTaskExecutor locks;
    private final ApplicationsProperties properties;

    ReminderScheduler(ReminderService reminders, LockingTaskExecutor locks, ApplicationsProperties properties) {
        this.reminders = reminders;
        this.locks = locks;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.applications.reminders.poll-interval:5m}",
            initialDelayString = "${app.applications.reminders.initial-delay:1m}")
    void poll() {
        run(Instant.now());
    }

    /** Sends the due reminders under the lock; false when another instance holds it (or the run could not start). */
    boolean run(Instant now) {
        LockConfiguration lock = new LockConfiguration(Instant.now(), LOCK_NAME,
                properties.reminders().lockAtMostFor(), java.time.Duration.ofSeconds(30));
        try {
            LockingTaskExecutor.TaskWithResult<Integer> task = () -> reminders.sendDue(now);
            return locks.executeWithLock(task, lock).wasExecuted();
        } catch (Throwable e) {
            log.error("The {} job could not run", LOCK_NAME, e);
            return false;
        }
    }
}
