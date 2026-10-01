package com.jobfinder.core.notifications.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the notifications module ({@code app.notifications.*}, docs/adr/0028-notifications.md).
 *
 * @param webBaseUrl          the web app's origin: job pages, settings and the unsubscribe page are linked from it
 * @param publicApiUrl        where core-api is reachable from a mail client for the RFC 8058 one-click POST; blank means
 *                            the web app's own proxy, {@code webBaseUrl + "/api/core"} (docs/adr/0011)
 * @param unsubscribeSecret   key of the unsubscribe tokens' signature; blank derives one from the JWT secret, so the
 *                            module needs no extra secret to start (set one in production to rotate them independently)
 * @param maxSavedSearches    saved searches one user may keep
 * @param unsubscribeTokenTtl how long an unsubscribe link in a sent email keeps working (long: people unsubscribe from
 *                            old mail)
 * @param settleDelay         jobs stored this recently are left for the next run: an ingestion transaction that started
 *                            before a run's cut-off may still be committing
 */
@ConfigurationProperties("app.notifications")
record NotificationProperties(
        @DefaultValue("http://localhost:3000") String webBaseUrl,
        @DefaultValue("") String publicApiUrl,
        @DefaultValue("no-reply@jobfinder.local") String mailFrom,
        @DefaultValue("") String unsubscribeSecret,
        @DefaultValue("20") int maxSavedSearches,
        @DefaultValue("730d") Duration unsubscribeTokenTtl,
        @DefaultValue("2m") Duration settleDelay,
        @DefaultValue Digest digest,
        @DefaultValue Alerts instant,
        @DefaultValue Delivery delivery) {

    NotificationProperties {
        if (maxSavedSearches < 1 || maxSavedSearches > 200) {
            throw new IllegalArgumentException("app.notifications.max-saved-searches must be between 1 and 200");
        }
        if (unsubscribeTokenTtl.compareTo(Duration.ofDays(30)) < 0) {
            throw new IllegalArgumentException("app.notifications.unsubscribe-token-ttl must be at least 30 days");
        }
        if (settleDelay.isNegative()) {
            throw new IllegalArgumentException("app.notifications.settle-delay must not be negative");
        }
    }

    /** Base URL of the one-click endpoint, without a trailing slash. */
    String apiBaseUrl() {
        String base = publicApiUrl == null || publicApiUrl.isBlank() ? webBaseUrl + "/api/core" : publicApiUrl;
        return strip(base);
    }

    String webBase() {
        return strip(webBaseUrl);
    }

    private static String strip(String url) {
        return url.replaceAll("/+$", "");
    }

    /**
     * The digest run: every hour at minute 5 by default it looks for users whose digest time (their hour, in their
     * time zone) has come. A digest later than {@code maxLateness} after its time is skipped, not sent late.
     *
     * @param maxItems jobs listed in one email ("and N more" links to the rest)
     * @param minScore the lowest feed score (0 to 100) that counts as a strong match for the "For you" digest
     * @param lookback a match the model scored longer ago than this is not news
     */
    record Digest(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 5 * * * *") String cron,
            @DefaultValue("UTC") String zone,
            @DefaultValue("10") int maxItems,
            @DefaultValue("70") int minScore,
            @DefaultValue("14d") Duration lookback,
            @DefaultValue("6h") Duration maxLateness,
            @DefaultValue("200") int userPageSize,
            @DefaultValue("30m") Duration lockAtMostFor) {

        Digest {
            if (maxItems < 1 || maxItems > 50) {
                throw new IllegalArgumentException("app.notifications.digest.max-items must be between 1 and 50");
            }
            if (minScore < 0 || minScore > 100) {
                throw new IllegalArgumentException("app.notifications.digest.min-score must be between 0 and 100");
            }
            if (maxLateness.isNegative() || maxLateness.isZero() || maxLateness.compareTo(Duration.ofHours(23)) > 0) {
                throw new IllegalArgumentException("app.notifications.digest.max-lateness must be up to 23 hours");
            }
            if (userPageSize < 1 || userPageSize > 1000) {
                throw new IllegalArgumentException("app.notifications.digest.user-page-size must be between 1 and 1000");
            }
        }
    }

    /**
     * Instant alerts. Matches are alerted when the nightly run (or any ranking run) refreshes a user's scores; saved
     * searches set to INSTANT are polled every {@code pollInterval}. A user gets at most {@code maxPerDay} alert emails
     * in any 24 hours, each listing at most {@code maxItemsPerAlert} jobs; what a cap holds back is not lost, it is
     * still unsent for the next alert or the digest.
     */
    record Alerts(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10m") Duration pollInterval,
            @DefaultValue("3") int maxPerDay,
            @DefaultValue("5") int maxItemsPerAlert,
            @DefaultValue("36h") Duration lookback,
            @DefaultValue("10m") Duration lockAtMostFor) {

        Alerts {
            if (pollInterval.toSeconds() < 30) {
                throw new IllegalArgumentException("app.notifications.instant.poll-interval must be at least 30s");
            }
            if (maxPerDay < 1 || maxPerDay > 50) {
                throw new IllegalArgumentException("app.notifications.instant.max-per-day must be between 1 and 50");
            }
            if (maxItemsPerAlert < 1 || maxItemsPerAlert > 50) {
                throw new IllegalArgumentException("app.notifications.instant.max-items-per-alert must be between 1 and 50");
            }
        }
    }

    /**
     * Sending: an email that fails is tried again (the digest run and the instant poll find it) up to
     * {@code maxAttempts} times in all, waiting {@code initialBackoff}, then twice as long, and so on, and only while its
     * window is open. A claimed email whose sender died is taken over after {@code staleAfter}. Log rows older than
     * {@code logRetention} are deleted (and with them "already sent" for jobs that old).
     */
    record Delivery(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("15m") Duration initialBackoff,
            @DefaultValue("30m") Duration staleAfter,
            @DefaultValue("180d") Duration logRetention) {

        Delivery {
            if (maxAttempts < 1 || maxAttempts > 10) {
                throw new IllegalArgumentException("app.notifications.delivery.max-attempts must be between 1 and 10");
            }
        }
    }
}
