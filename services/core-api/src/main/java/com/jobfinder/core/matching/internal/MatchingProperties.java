package com.jobfinder.core.matching.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The matching engine's settings ({@code app.matching.*}, docs/adr/0026-matching-engine.md).
 *
 * @param promptVersion     the ai-service prompt scores are made with, for example {@code match_scoring/v1}. It is part
 *                          of the cache key and is sent with every request, so changing it re-scores everything
 *                          on demand and never mixes two prompts' scores under one key
 * @param recallLimit       how many jobs the recall keeps by cosine similarity (PLAN.md section 7: 300)
 * @param rerankTop         how many of the best stage-2 jobs the model re-ranks (PLAN.md section 7: 30)
 * @param weights           the stage-2 blend; see {@link Weights}
 * @param recencyHalfLife   the age at which a job's recency component halves
 * @param seniorityBand     how many seniority steps either side of the candidate's own the stage-1 filter allows
 *                          (0 means the same level only; 5 or more allows every level)
 */
@ConfigurationProperties("app.matching")
record MatchingProperties(
        @DefaultValue("match_scoring/v1") String promptVersion,
        @DefaultValue("300") int recallLimit,
        @DefaultValue("30") int rerankTop,
        @DefaultValue Weights weights,
        @DefaultValue("21d") Duration recencyHalfLife,
        @DefaultValue("1") int seniorityBand,
        @DefaultValue Llm llm,
        @DefaultValue Retention retention,
        @DefaultValue Batch batch) {

    MatchingProperties {
        if (promptVersion == null || !promptVersion.matches("match_scoring/v[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException("app.matching.prompt-version must look like match_scoring/v1");
        }
        if (recallLimit < 1 || recallLimit > 1000) {
            throw new IllegalArgumentException("app.matching.recall-limit must be between 1 and 1000");
        }
        if (rerankTop < 1 || rerankTop > recallLimit) {
            throw new IllegalArgumentException("app.matching.rerank-top must be between 1 and the recall limit");
        }
        if (recencyHalfLife.isZero() || recencyHalfLife.isNegative()) {
            throw new IllegalArgumentException("app.matching.recency-half-life must be positive");
        }
        if (seniorityBand < 0) {
            throw new IllegalArgumentException("app.matching.seniority-band must not be negative");
        }
    }

    /**
     * The stage-2 weights: each between 0 and 1, and together exactly 1 (within 0.001), so the blend is a weighted
     * mean and its 0-to-100 scale means the same thing under any configuration. A component that is unknown for a
     * job (no embedding yet, no skills listed) leaves the blend, and the remaining weights are rescaled to sum to 1.
     */
    record Weights(@DefaultValue("0.6") double vector, @DefaultValue("0.3") double skills,
            @DefaultValue("0.1") double recency) {

        Weights {
            for (double w : new double[] { vector, skills, recency }) {
                if (!(w >= 0 && w <= 1)) {
                    throw new IllegalArgumentException("app.matching.weights.* must each be between 0 and 1");
                }
            }
            if (Math.abs(vector + skills + recency - 1.0) > 0.001) {
                throw new IllegalArgumentException(
                        "app.matching.weights.vector + skills + recency must add up to 1 (got "
                                + (vector + skills + recency) + ")");
            }
        }
    }

    /**
     * @param batchSize        jobs per request to ai-service (which may split a request further under its token
     *                         budget); one request is one billed model call or a few
     * @param descriptionChars the job description is cut to this many characters before it is hashed and sent
     * @param connectTimeout   how long to wait for ai-service to accept a connection
     * @param readTimeout      how long to wait for its answer to one request
     */
    record Llm(@DefaultValue("10") int batchSize, @DefaultValue("3000") int descriptionChars,
            @DefaultValue("2s") Duration connectTimeout, @DefaultValue("120s") Duration readTimeout) {

        Llm {
            if (batchSize < 1 || batchSize > 50) {
                throw new IllegalArgumentException("app.matching.llm.batch-size must be between 1 and 50");
            }
            if (descriptionChars < 200) {
                throw new IllegalArgumentException("app.matching.llm.description-chars must be at least 200");
            }
        }
    }

    /**
     * Old rows are pruned at the end of each nightly run. A score for a resume version the user no longer matches
     * with (a newer version has been scored since) goes after {@code supersededAfter}; any score goes after
     * {@code maxAge}. A pruned score that is needed again is simply computed again.
     */
    record Retention(@DefaultValue("14d") Duration supersededAfter, @DefaultValue("90d") Duration maxAge) {

        Retention {
            if (supersededAfter.isNegative() || maxAge.isNegative() || maxAge.compareTo(supersededAfter) < 0) {
                throw new IllegalArgumentException(
                        "app.matching.retention: durations must not be negative and max-age at least superseded-after");
            }
        }
    }

    /**
     * The nightly run. A user is <em>active</em> when they signed in (or renewed a session) within
     * {@code activeWithin} and have a primary resume with parsed content and saved preferences. A run stops
     * starting new users after {@code maxUsersPerRun} users or {@code maxAiRequestsPerRun} requests to ai-service,
     * whichever comes first; the next night's run picks up from scratch (cached scores make that cheap).
     */
    record Batch(@DefaultValue("true") boolean enabled, @DefaultValue("0 30 2 * * *") String cron,
            @DefaultValue("UTC") String zone, @DefaultValue("14d") Duration activeWithin,
            @DefaultValue("500") int maxUsersPerRun, @DefaultValue("3000") int maxAiRequestsPerRun,
            @DefaultValue("100") int userPageSize, @DefaultValue("2h") Duration lockAtMostFor) {

        Batch {
            if (activeWithin.isZero() || activeWithin.isNegative()) {
                throw new IllegalArgumentException("app.matching.batch.active-within must be positive");
            }
            if (maxUsersPerRun < 1 || maxAiRequestsPerRun < 1 || userPageSize < 1) {
                throw new IllegalArgumentException(
                        "app.matching.batch.max-users-per-run, max-ai-requests-per-run and user-page-size must be positive");
            }
            if (lockAtMostFor.isZero() || lockAtMostFor.isNegative()) {
                throw new IllegalArgumentException("app.matching.batch.lock-at-most-for must be positive");
            }
        }
    }
}
