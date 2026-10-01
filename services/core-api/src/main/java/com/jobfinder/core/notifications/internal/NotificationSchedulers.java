package com.jobfinder.core.notifications.internal;

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
 * The clocks of the notifications module (docs/adr/0028-notifications.md). Both jobs run under a ShedLock lock (the
 * table and provider ingestion and matching use), so one instance runs each at a time however many are deployed, and
 * both can be switched off by configuration. A run that throws is logged and the next one starts normally.
 */
final class NotificationSchedulers {

    private static final Logger log = LoggerFactory.getLogger(NotificationSchedulers.class);

    private NotificationSchedulers() {
    }

    private static <T> boolean run(LockingTaskExecutor locks, String name, Duration lockAtMostFor,
            LockingTaskExecutor.TaskWithResult<T> task) {
        LockConfiguration lock = new LockConfiguration(Instant.now(), name, lockAtMostFor, Duration.ofSeconds(30));
        try {
            return locks.executeWithLock(task, lock).wasExecuted();
        } catch (Throwable e) {
            log.error("The {} job could not run", name, e);
            return false;
        }
    }

    /** The daily and weekly digests: every hour (at minute 5) it sends the digests whose time has come. */
    @Configuration
    @ConditionalOnProperty(prefix = "app.notifications.digest", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class DigestScheduler {

        static final String LOCK_NAME = "notifications:digest";

        private final DigestService digests;
        private final LockingTaskExecutor locks;
        private final NotificationProperties properties;

        DigestScheduler(DigestService digests, LockingTaskExecutor locks, NotificationProperties properties) {
            this.digests = digests;
            this.locks = locks;
            this.properties = properties;
        }

        @Scheduled(cron = "${app.notifications.digest.cron:0 5 * * * *}", zone = "${app.notifications.digest.zone:UTC}")
        void hourly() {
            run();
        }

        /** Runs the digests under the lock; false when another instance holds it. */
        boolean run() {
            return NotificationSchedulers.run(locks, LOCK_NAME, properties.digest().lockAtMostFor(),
                    () -> digests.runDue(Instant.now()));
        }
    }

    /** Instant alerts for saved searches set to INSTANT: polled every few minutes. */
    @Configuration
    @ConditionalOnProperty(prefix = "app.notifications.instant", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class InstantScheduler {

        static final String LOCK_NAME = "notifications:instant";

        private final InstantAlertService alerts;
        private final LockingTaskExecutor locks;
        private final NotificationProperties properties;

        InstantScheduler(InstantAlertService alerts, LockingTaskExecutor locks, NotificationProperties properties) {
            this.alerts = alerts;
            this.locks = locks;
            this.properties = properties;
        }

        @Scheduled(fixedDelayString = "${app.notifications.instant.poll-interval:10m}",
                initialDelayString = "${app.notifications.instant.initial-delay:1m}")
        void poll() {
            run();
        }

        boolean run() {
            return NotificationSchedulers.run(locks, LOCK_NAME, properties.instant().lockAtMostFor(),
                    () -> alerts.pollSearches(Instant.now()));
        }
    }
}
