package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure logic: which overrides are honoured, and when a source is due. */
class SourceSettingsTests {

    private static final IngestionProperties.Defaults DEFAULTS = new IngestionProperties.Defaults(360, 900, 2.0, 3,
            Duration.ofSeconds(1), Duration.ofSeconds(30), 50f, 5, Duration.ofMinutes(5));

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void withNoOverridesTheDefaultsApply() {
        SourceSettings settings = SourceSettings.resolve(DEFAULTS, null, null, null, null);

        assertThat(settings).isEqualTo(new SourceSettings(360, 900, 2.0, 3));
    }

    @Test
    void validOverridesWinOverTheDefaults() {
        SourceSettings settings = SourceSettings.resolve(DEFAULTS, "60", "0", "0.5", "5");

        assertThat(settings).isEqualTo(new SourceSettings(60, 0, 0.5, 5));
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc", "", "0", "-5", "1.5" })
    void anUnusableIntervalFallsBackToTheDefault(String garbage) {
        assertThat(SourceSettings.resolve(DEFAULTS, garbage, null, null, null).intervalMinutes()).isEqualTo(360);
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc", "0", "-1" })
    void anUnusableRateLimitFallsBackToTheDefault(String garbage) {
        assertThat(SourceSettings.resolve(DEFAULTS, null, null, garbage, null).requestsPerSecond()).isEqualTo(2.0);
    }

    @Test
    void aSourceThatHasNeverRunIsDue() {
        assertThat(new SourceSettings(360, 900, 2, 3).isDue("GREENHOUSE", null, NOW)).isTrue();
    }

    @Test
    void aSourceIsNotDueBeforeItsIntervalHasPassed() {
        SourceSettings settings = new SourceSettings(60, 0, 2, 3);

        assertThat(settings.isDue("GREENHOUSE", NOW.minus(Duration.ofMinutes(59)), NOW)).isFalse();
        assertThat(settings.isDue("GREENHOUSE", NOW.minus(Duration.ofMinutes(60)), NOW)).isTrue();
        assertThat(settings.isDue("GREENHOUSE", NOW.minus(Duration.ofMinutes(61)), NOW)).isTrue();
    }

    @Test
    void jitterDelaysASourceByAStableAmountBelowTheJitterCeiling() {
        SourceSettings settings = new SourceSettings(60, 900, 2, 3);

        Duration jitter = settings.jitter("GREENHOUSE");

        assertThat(jitter).isBetween(Duration.ZERO, Duration.ofSeconds(899));
        assertThat(settings.jitter("GREENHOUSE")).as("the same every time").isEqualTo(jitter);
        Instant lastRun = NOW.minus(Duration.ofMinutes(60)).minus(jitter);
        assertThat(settings.isDue("GREENHOUSE", lastRun.plusSeconds(1), NOW)).isFalse();
        assertThat(settings.isDue("GREENHOUSE", lastRun, NOW)).isTrue();
    }

    @Test
    void differentSourcesGetDifferentOffsets() {
        SourceSettings settings = new SourceSettings(60, 900, 2, 3);

        assertThat(java.util.stream.Stream.of("GREENHOUSE", "LEVER", "ASHBY", "WORKABLE", "ADZUNA", "JSEARCH")
                .map(settings::jitter).distinct()).hasSizeGreaterThan(1);
    }

    @Test
    void noJitterMeansNoOffset() {
        assertThat(new SourceSettings(60, 0, 2, 3).jitter("GREENHOUSE")).isEqualTo(Duration.ZERO);
    }
}
