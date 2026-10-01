package com.jobfinder.core.jobs.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Job search tuning ({@code app.search.*}, docs/adr/0023-job-search.md).
 *
 * @param similarNeighbours how many nearest neighbours "similar jobs" can ever return (the list is bounded; its
 *                          pages are slices of the top N)
 * @param efSearch          pgvector {@code hnsw.ef_search} for similar-jobs queries: the candidate list the index
 *                          keeps while it searches. Must be at least {@code similarNeighbours}, or the index cannot
 *                          return that many.
 * @param statementTimeout  a search query still running after this is cancelled and answered with 503
 */
@ConfigurationProperties("app.search")
record SearchProperties(
        @DefaultValue("100") int similarNeighbours,
        @DefaultValue("100") int efSearch,
        @DefaultValue("3s") Duration statementTimeout) {

    SearchProperties {
        if (similarNeighbours < 1 || similarNeighbours > 1000) {
            throw new IllegalArgumentException("app.search.similar-neighbours must be between 1 and 1000");
        }
        if (efSearch < similarNeighbours || efSearch > 1000) {
            throw new IllegalArgumentException(
                    "app.search.ef-search must be between similar-neighbours and 1000 (pgvector's maximum)");
        }
        if (statementTimeout.toMillis() < 100) {
            throw new IllegalArgumentException("app.search.statement-timeout must be at least 100ms");
        }
    }
}
