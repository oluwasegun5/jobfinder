package com.jobfinder.core.ingestion.internal.aggregator;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the aggregator adapters ({@code app.ingestion.aggregators.*}, ADR 0021). The two keyed sources
 * read their credentials from the environment through {@code application.yml} ({@code ADZUNA_APP_ID},
 * {@code ADZUNA_APP_KEY}, {@code JSEARCH_RAPIDAPI_KEY}); a blank key leaves the source registered but skipped.
 * Base URLs are properties so tests can point them at WireMock.
 *
 * <p>
 * Quota handling is per source and has three levers, whose defaults keep each source inside its free tier:
 * {@code maxPagesPerTarget} (the cost of one target in one run), {@code maxRequestsPerDay} (a hard stop per
 * UTC day, counted in memory across runs and targets) and {@code minRequestInterval} (pacing between two
 * requests of the source). The run frequency is {@code intervalMinutes} in the source's {@code sources.config}.
 */
@ConfigurationProperties("app.ingestion.aggregators")
record AggregatorProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("33554432") long maxResponseBytes,
        @DefaultValue Adzuna adzuna,
        @DefaultValue JSearch jsearch,
        @DefaultValue Remotive remotive,
        @DefaultValue Arbeitnow arbeitnow,
        @DefaultValue RemoteOk remoteok) {

    private static final Set<String> DATE_POSTED = Set.of("all", "today", "3days", "week", "month");

    AggregatorProperties {
        if (maxResponseBytes < 1024) {
            throw new IllegalArgumentException("app.ingestion.aggregators.max-response-bytes must be at least 1024");
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException("app.ingestion.aggregators." + message);
        }
    }

    /** Adzuna: free keys, documented limits 25 a minute, 250 a day, 1,000 a week, 2,500 a month. */
    record Adzuna(
            @DefaultValue("https://api.adzuna.com") String baseUrl,
            String appId,
            String appKey,
            @DefaultValue("50") int resultsPerPage,
            @DefaultValue("2") int maxPagesPerTarget,
            @DefaultValue("30") int maxDaysOld,
            @DefaultValue("200") int maxRequestsPerDay,
            @DefaultValue("2500ms") Duration minRequestInterval) {

        Adzuna {
            check(resultsPerPage >= 1 && resultsPerPage <= 50, "adzuna.results-per-page must be between 1 and 50");
            check(maxPagesPerTarget >= 1, "adzuna.max-pages-per-target must be at least 1");
            check(maxDaysOld >= 1, "adzuna.max-days-old must be at least 1");
            check(maxRequestsPerDay >= 0, "adzuna.max-requests-per-day must not be negative");
            check(!minRequestInterval.isNegative(), "adzuna.min-request-interval must not be negative");
        }

        boolean configured() {
            return present(appId) && present(appKey);
        }

        @Override
        public String toString() {
            return "Adzuna[baseUrl=" + baseUrl + ", credentials=" + (configured() ? "set" : "missing") + "]";
        }
    }

    /** JSearch on RapidAPI: free plan of 200 requests a month, so the default is 6 a day. */
    record JSearch(
            @DefaultValue("https://jsearch.p.rapidapi.com") String baseUrl,
            @DefaultValue("jsearch.p.rapidapi.com") String apiHost,
            String apiKey,
            @DefaultValue("1") int maxPagesPerTarget,
            @DefaultValue("month") String datePosted,
            @DefaultValue("6") int maxRequestsPerDay,
            @DefaultValue("1s") Duration minRequestInterval) {

        JSearch {
            check(maxPagesPerTarget >= 1, "jsearch.max-pages-per-target must be at least 1");
            check(DATE_POSTED.contains(datePosted), "jsearch.date-posted must be one of " + DATE_POSTED);
            check(maxRequestsPerDay >= 0, "jsearch.max-requests-per-day must not be negative");
            check(!minRequestInterval.isNegative(), "jsearch.min-request-interval must not be negative");
        }

        boolean configured() {
            return present(apiKey);
        }

        @Override
        public String toString() {
            return "JSearch[baseUrl=" + baseUrl + ", credentials=" + (configured() ? "set" : "missing") + "]";
        }
    }

    /** Remotive: advises at most 4 requests a day and blocks more than 2 a minute. */
    record Remotive(
            @DefaultValue("https://remotive.com") String baseUrl,
            @DefaultValue("4") int maxRequestsPerDay,
            @DefaultValue("35s") Duration minRequestInterval) {

        Remotive {
            check(maxRequestsPerDay >= 0, "remotive.max-requests-per-day must not be negative");
            check(!minRequestInterval.isNegative(), "remotive.min-request-interval must not be negative");
        }
    }

    /** Arbeitnow: no key, no documented limit; one page is about 2.5 MB, so pages are capped. */
    record Arbeitnow(
            @DefaultValue("https://www.arbeitnow.com") String baseUrl,
            @DefaultValue("5") int maxPagesPerTarget,
            @DefaultValue("100") int maxRequestsPerDay,
            @DefaultValue("1s") Duration minRequestInterval) {

        Arbeitnow {
            check(maxPagesPerTarget >= 1, "arbeitnow.max-pages-per-target must be at least 1");
            check(maxRequestsPerDay >= 0, "arbeitnow.max-requests-per-day must not be negative");
            check(!minRequestInterval.isNegative(), "arbeitnow.min-request-interval must not be negative");
        }
    }

    /** RemoteOK: no key, no documented limit; the feed is one request. */
    record RemoteOk(
            @DefaultValue("https://remoteok.com") String baseUrl,
            @DefaultValue("24") int maxRequestsPerDay,
            @DefaultValue("5s") Duration minRequestInterval) {

        RemoteOk {
            check(maxRequestsPerDay >= 0, "remoteok.max-requests-per-day must not be negative");
            check(!minRequestInterval.isNegative(), "remoteok.min-request-interval must not be negative");
        }
    }
}
