package com.jobfinder.core.ingestion.internal.aggregator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class RequestGateTests {

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T10:00:00Z"));

        void set(String instant) {
            now.set(Instant.parse(instant));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void grantsTheDaysBudgetThenRefuses() {
        RequestGate gate = new RequestGate("Test", 3, Duration.ZERO, new MutableClock());

        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
        assertThat(gate.tryAcquire()).isFalse();
    }

    @Test
    void theBudgetIsGivenBackAtMidnightUtc() {
        MutableClock clock = new MutableClock();
        RequestGate gate = new RequestGate("Test", 1, Duration.ZERO, clock);

        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
        clock.set("2026-10-01T23:59:59Z");
        assertThat(gate.tryAcquire()).isFalse();
        clock.set("2026-10-02T00:00:01Z");
        assertThat(gate.tryAcquire()).isTrue();
    }

    @Test
    void aBudgetOfZeroNeverGrants() {
        assertThat(new RequestGate("Test", 0, Duration.ZERO, new MutableClock()).tryAcquire()).isFalse();
    }

    @Test
    void pacesTwoRequestsByTheMinimumInterval() {
        RequestGate gate = new RequestGate("Test", 10, Duration.ofMillis(150), new MutableClock());

        long start = System.nanoTime();
        gate.tryAcquire();
        gate.tryAcquire();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(140);
    }
}
