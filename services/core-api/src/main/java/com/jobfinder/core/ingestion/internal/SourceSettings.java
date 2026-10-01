package com.jobfinder.core.ingestion.internal;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A source's effective tuning: the global defaults, overridden by the flat keys
 * {@code intervalMinutes}, {@code jitterSeconds}, {@code requestsPerSecond} and
 * {@code retryMaxAttempts} in {@code sources.config}. A missing or unusable override (not a number,
 * out of range) falls back to the default instead of stopping the scheduler.
 */
record SourceSettings(int intervalMinutes, int jitterSeconds, double requestsPerSecond, int retryMaxAttempts) {

    private static final Logger log = LoggerFactory.getLogger(SourceSettings.class);

    static SourceSettings resolve(IngestionProperties.Defaults defaults, String interval, String jitter,
            String requestsPerSecond, String retryMaxAttempts) {
        return new SourceSettings(
                parse("intervalMinutes", interval, defaults.intervalMinutes(), Integer::parseInt, v -> v >= 1),
                parse("jitterSeconds", jitter, defaults.jitterSeconds(), Integer::parseInt, v -> v >= 0),
                parse("requestsPerSecond", requestsPerSecond, defaults.requestsPerSecond(), Double::parseDouble,
                        v -> v > 0),
                parse("retryMaxAttempts", retryMaxAttempts, defaults.retryMaxAttempts(), Integer::parseInt,
                        v -> v >= 1));
    }

    /**
     * Whether a source last run at {@code lastRunAt} is due. Each source gets a fixed offset of up to
     * {@code jitterSeconds} derived from its code, so sources with the same interval do not all
     * start in the same minute, yet each one's schedule is stable across restarts.
     */
    boolean isDue(String sourceCode, Instant lastRunAt, Instant now) {
        if (lastRunAt == null) {
            return true;
        }
        Duration wait = Duration.ofMinutes(intervalMinutes).plus(jitter(sourceCode));
        return !now.isBefore(lastRunAt.plus(wait));
    }

    /** When a source last run at {@code lastRunAt} is next due; {@code null} if it never ran (it is due at once). */
    Instant nextDue(String sourceCode, Instant lastRunAt) {
        return lastRunAt == null ? null
                : lastRunAt.plus(Duration.ofMinutes(intervalMinutes).plus(jitter(sourceCode)));
    }

    Duration jitter(String sourceCode) {
        if (jitterSeconds <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(Math.floorMod(sourceCode.hashCode(), jitterSeconds));
    }

    private static <T> T parse(String key, String raw, T fallback, java.util.function.Function<String, T> parser,
            java.util.function.Predicate<T> valid) {
        if (raw == null) {
            return fallback;
        }
        try {
            T value = parser.apply(raw.trim());
            if (valid.test(value)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the warning
        }
        log.warn("Ignoring unusable sources.config value {}={}; using {}", key, raw, fallback);
        return fallback;
    }
}
