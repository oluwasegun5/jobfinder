package com.jobfinder.core.applications.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.applications.*} (docs/adr/0032-application-tracker.md).
 *
 * @param maxPerUser the applications one user may keep
 */
@ConfigurationProperties("app.applications")
record ApplicationsProperties(
        @DefaultValue("2000") int maxPerUser,
        @DefaultValue Reminders reminders,
        @DefaultValue FollowUp followUp) {

    ApplicationsProperties {
        if (maxPerUser < 1 || maxPerUser > 100_000) {
            throw new IllegalArgumentException("app.applications.max-per-user must be between 1 and 100000");
        }
    }

    /**
     * The reminder sender: every {@code pollInterval} it sends the PENDING reminders that are due, at most
     * {@code batchSize} per run, under a ShedLock lock. A reminder is tried at most {@code maxAttempts} times, waiting
     * {@code retryAfter} between tries (which is also how long a claim whose sender died is held). A user keeps at most
     * {@code maxPendingPerApplication} pending reminders on one application, none further than {@code maxHorizon} away.
     */
    record Reminders(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") Duration pollInterval,
            @DefaultValue("1m") Duration initialDelay,
            @DefaultValue("100") int batchSize,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("15m") Duration retryAfter,
            @DefaultValue("10m") Duration lockAtMostFor,
            @DefaultValue("20") int maxPendingPerApplication,
            @DefaultValue("366d") Duration maxHorizon) {

        Reminders {
            if (pollInterval.toSeconds() < 30) {
                throw new IllegalArgumentException("app.applications.reminders.poll-interval must be at least 30s");
            }
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("app.applications.reminders.batch-size must be between 1 and 1000");
            }
            if (maxAttempts < 1 || maxAttempts > 10) {
                throw new IllegalArgumentException("app.applications.reminders.max-attempts must be between 1 and 10");
            }
            if (maxPendingPerApplication < 1 || maxPendingPerApplication > 100) {
                throw new IllegalArgumentException(
                        "app.applications.reminders.max-pending-per-application must be between 1 and 100");
            }
        }
    }

    /**
     * The follow-up email draft: the prompt sent to ai-service (and recorded with every metered call), and how long the
     * call may take. The job text is cut to {@code descriptionChars} before it is sent.
     */
    record FollowUp(
            @DefaultValue("follow_up_email/v1") String promptVersion,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout,
            @DefaultValue("20000") int descriptionChars) {

        FollowUp {
            if (promptVersion == null || !promptVersion.matches("follow_up_email/v[1-9][0-9]{0,2}")) {
                throw new IllegalArgumentException(
                        "app.applications.follow-up.prompt-version must look like follow_up_email/v1");
            }
        }
    }
}
