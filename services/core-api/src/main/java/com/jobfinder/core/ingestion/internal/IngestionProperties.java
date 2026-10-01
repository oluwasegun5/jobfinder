package com.jobfinder.core.ingestion.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ingestion pipeline settings ({@code app.ingestion.*}). {@code defaults} apply to every source; a
 * source overrides the schedule, rate limit and retry attempts in its own {@code sources.config}
 * (see {@link SourceSettings}). The scheduler's polling rhythm is read by {@code @Scheduled}
 * straight from {@code app.ingestion.scheduler.poll-interval-ms} / {@code initial-delay-ms}.
 *
 * <p>
 * {@code lockAtMostFor} bounds how long a crashed instance can keep a source locked; it must exceed
 * the longest legitimate run.
 */
@ConfigurationProperties("app.ingestion")
record IngestionProperties(@DefaultValue Scheduler scheduler, @DefaultValue("30m") Duration lockAtMostFor,
        @DefaultValue Defaults defaults, @DefaultValue Expiry expiry, @DefaultValue Seed seed) {

    IngestionProperties {
        if (lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
            throw new IllegalArgumentException("app.ingestion.lock-at-most-for must be positive");
        }
    }

    record Scheduler(@DefaultValue("true") boolean enabled) {
    }

    /**
     * The seed list of source targets (ADR 0020), loaded on startup. Loading only adds targets that are
     * missing, so it is safe on every start and never undoes an admin's change.
     */
    record Seed(@DefaultValue("true") boolean enabled,
            @DefaultValue("classpath:ingestion/seed-targets.json") String location) {
    }

    /**
     * The expiry rules of PLAN.md section 6: a listing that a full-listing source (ATS, scrape) has
     * not returned for {@code missedRuns} consecutive runs is gone, and so is an aggregator listing
     * not seen for {@code aggregatorStaleAfter}. A job expires when none of its listings is left.
     */
    record Expiry(@DefaultValue("2") int missedRuns, @DefaultValue("45d") Duration aggregatorStaleAfter) {

        Expiry {
            if (missedRuns < 1) {
                throw new IllegalArgumentException("app.ingestion.expiry.missed-runs must be at least 1");
            }
            if (aggregatorStaleAfter.isNegative() || aggregatorStaleAfter.isZero()) {
                throw new IllegalArgumentException("app.ingestion.expiry.aggregator-stale-after must be positive");
            }
        }
    }

    record Defaults(
            @DefaultValue("360") int intervalMinutes,
            @DefaultValue("900") int jitterSeconds,
            @DefaultValue("2") double requestsPerSecond,
            @DefaultValue("3") int retryMaxAttempts,
            @DefaultValue("1s") Duration retryInitialBackoff,
            @DefaultValue("30s") Duration rateLimitTimeout,
            @DefaultValue("50") float breakerFailureRateThreshold,
            @DefaultValue("5") int breakerMinimumCalls,
            @DefaultValue("5m") Duration breakerOpenDuration) {

        Defaults {
            if (intervalMinutes < 1) {
                throw new IllegalArgumentException("app.ingestion.defaults.interval-minutes must be at least 1");
            }
            if (jitterSeconds < 0) {
                throw new IllegalArgumentException("app.ingestion.defaults.jitter-seconds must not be negative");
            }
            if (requestsPerSecond <= 0) {
                throw new IllegalArgumentException("app.ingestion.defaults.requests-per-second must be positive");
            }
            if (retryMaxAttempts < 1) {
                throw new IllegalArgumentException("app.ingestion.defaults.retry-max-attempts must be at least 1");
            }
            if (breakerMinimumCalls < 1) {
                throw new IllegalArgumentException("app.ingestion.defaults.breaker-minimum-calls must be at least 1");
            }
        }
    }
}
