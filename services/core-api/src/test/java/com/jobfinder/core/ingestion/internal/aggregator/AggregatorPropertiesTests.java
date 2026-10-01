package com.jobfinder.core.ingestion.internal.aggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AggregatorPropertiesTests {

    private static AggregatorProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("app.ingestion.aggregators", AggregatorProperties.class).get();
    }

    @Test
    void theDefaultsKeepEachSourceInsideItsFreeTier() {
        AggregatorProperties defaults = bind(Map.of("app.ingestion.aggregators.connect-timeout", "5s"));

        assertThat(defaults.adzuna().maxRequestsPerDay()).isLessThan(250);
        assertThat(defaults.adzuna().resultsPerPage()).isEqualTo(50);
        assertThat(defaults.adzuna().minRequestInterval()).isGreaterThanOrEqualTo(Duration.ofMillis(2400));
        assertThat(defaults.jsearch().maxRequestsPerDay() * 31).isLessThanOrEqualTo(200);
        assertThat(defaults.jsearch().maxPagesPerTarget()).isEqualTo(1);
        assertThat(defaults.remotive().maxRequestsPerDay()).isLessThanOrEqualTo(4);
        assertThat(defaults.remotive().minRequestInterval()).isGreaterThan(Duration.ofSeconds(30));
        assertThat(defaults.arbeitnow().maxPagesPerTarget()).isEqualTo(5);
        assertThat(defaults.adzuna().configured()).isFalse();
        assertThat(defaults.jsearch().configured()).isFalse();
    }

    @Test
    void keysAreReadFromTheirPropertiesAndNeverPrinted() {
        AggregatorProperties bound = bind(Map.of(
                "app.ingestion.aggregators.adzuna.app-id", "visible-id-marker",
                "app.ingestion.aggregators.adzuna.app-key", "visible-key-marker",
                "app.ingestion.aggregators.jsearch.api-key", "visible-rapid-marker"));

        assertThat(bound.adzuna().configured()).isTrue();
        assertThat(bound.jsearch().configured()).isTrue();
        assertThat(bound.toString()).doesNotContain("visible-id-marker", "visible-key-marker", "visible-rapid-marker");
    }

    @Test
    void aBlankKeyCountsAsMissing() {
        AggregatorProperties bound = bind(Map.of(
                "app.ingestion.aggregators.adzuna.app-id", "",
                "app.ingestion.aggregators.adzuna.app-key", "key",
                "app.ingestion.aggregators.jsearch.api-key", "  "));

        assertThat(bound.adzuna().configured()).isFalse();
        assertThat(bound.jsearch().configured()).isFalse();
    }

    @Test
    void unusableQuotaSettingsAreRefusedAtStartup() {
        assertThatThrownBy(() -> bind(Map.of("app.ingestion.aggregators.adzuna.results-per-page", "500")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("app.ingestion.aggregators.arbeitnow.max-pages-per-target", "0")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("app.ingestion.aggregators.jsearch.date-posted", "yesterday")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
