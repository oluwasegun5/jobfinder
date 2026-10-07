package com.jobfinder.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CachedGaugeTests {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void queriesOnceWithinTheTtlAndSurvivesGarbageCollection() {
        AtomicInteger calls = new AtomicInteger();
        CachedGauge.register(registry, "test.gauge", "d", Tags.empty(), Duration.ofHours(1), calls::incrementAndGet);

        System.gc();

        assertThat(registry.get("test.gauge").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("test.gauge").gauge().value()).isEqualTo(1.0);
        assertThat(calls).hasValue(1);
    }

    @Test
    void queriesAgainAfterTheTtl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CachedGauge.register(registry, "test.gauge", "d", Tags.empty(), Duration.ZERO, calls::incrementAndGet);

        registry.get("test.gauge").gauge().value();
        registry.get("test.gauge").gauge().value();

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aFailingQueryIsNoSampleNotAFailedScrape() {
        CachedGauge.register(registry, "test.gauge", "d", Tags.empty(), Duration.ofMinutes(1), () -> {
            throw new IllegalStateException("database down");
        });

        assertThat(registry.get("test.gauge").gauge().value()).isNaN();
    }
}
