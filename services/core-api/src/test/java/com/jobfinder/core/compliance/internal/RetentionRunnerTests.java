package com.jobfinder.core.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.compliance.RetentionProperties;
import com.jobfinder.core.compliance.RetentionTask;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** The runner calls every task, isolates failures, and counts what was removed; the properties refuse nonsense. */
class RetentionRunnerTests {

    private static RetentionTask task(String name, int removed) {
        return new RetentionTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int purge(Instant now) {
                return removed;
            }
        };
    }

    @Test
    void aFailingTaskIsReportedAndNeverStopsTheOthers() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RetentionTask broken = new RetentionTask() {
            @Override
            public String name() {
                return "b-broken";
            }

            @Override
            public int purge(Instant now) {
                throw new IllegalStateException("storage down");
            }
        };
        RetentionRunner runner = new RetentionRunner(List.of(task("c-last", 2), broken, task("a-first", 5)), meters);

        Map<String, Integer> result = runner.runAll(Instant.now());

        assertThat(result).containsExactly(Map.entry("a-first", 5), Map.entry("b-broken", -1),
                Map.entry("c-last", 2));
        assertThat(meters.counter("retention_purged_total", "task", "a-first").count()).isEqualTo(5.0);
        assertThat(meters.counter("retention_failures_total", "task", "b-broken").count()).isEqualTo(1.0);
    }

    @Test
    void everyPeriodMustBePositive() {
        RetentionProperties defaults = new RetentionProperties(true, Duration.ofHours(24), Duration.ofMinutes(10),
                Duration.ofDays(30), Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(180),
                Duration.ofDays(365), Duration.ofDays(90));
        assertThat(defaults.rawPostings()).isEqualTo(Duration.ofDays(30));

        assertThatThrownBy(() -> new RetentionProperties(true, Duration.ofHours(24), Duration.ZERO,
                Duration.ZERO, Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(365),
                Duration.ofDays(90))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("raw-postings");
    }
}
