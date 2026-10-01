package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.ingestion.IngestionTestSupport;

/** What the scheduler picks up on a tick: due, enabled sources, and nothing else. */
class IngestionSchedulerTests extends IngestionTestSupport {

    @Autowired
    private IngestionScheduler scheduler;

    @BeforeEach
    void onlyTheFakeSourceIsInPlay() {
        jdbc.update("update sources set enabled = false where code in ('FAKE_RETRY', 'FAKE_BREAKER')");
        addTarget(FAKE, "acme");
        fake.postings("acme", 2);
    }

    @Test
    void aSourceThatHasNeverRunIsStartedOnTheNextTick() throws Exception {
        awaitAll(scheduler.tick(Instant.now()));

        assertThat(runCount(FAKE)).isEqualTo(1);
        assertThat(rawPostingCount(FAKE)).isEqualTo(2);
    }

    @Test
    void aSourceIsNotStartedAgainUntilItsIntervalAndJitterHavePassed() throws Exception {
        Instant now = Instant.now();
        awaitAll(scheduler.tick(now));

        awaitAll(scheduler.tick(now.plus(Duration.ofHours(1))));
        assertThat(runCount(FAKE)).as("one hour later: not due").isEqualTo(1);

        // Default interval is 6 hours plus at most 15 minutes of jitter.
        awaitAll(scheduler.tick(now.plus(Duration.ofHours(7))));
        assertThat(runCount(FAKE)).as("seven hours later: due").isEqualTo(2);
    }

    @Test
    void aDisabledSourceIsLeftAlone() throws Exception {
        jdbc.update("update sources set enabled = false where code = 'FAKE'");

        List<Future<?>> started = scheduler.tick(Instant.now());

        assertThat(started).isEmpty();
        assertThat(runCount(FAKE)).isZero();
    }

    @Test
    void aSourceTunedToRunOftenIsDueSooner() throws Exception {
        jdbc.update("update sources set config = '{\"intervalMinutes\": 5, \"jitterSeconds\": 0}'::jsonb "
                + "where code = 'FAKE'");
        Instant now = Instant.now();
        awaitAll(scheduler.tick(now));

        awaitAll(scheduler.tick(now.plus(Duration.ofMinutes(6))));

        assertThat(runCount(FAKE)).isEqualTo(2);
    }

    @Test
    void aTickWhileASourceIsStillRunningDoesNotStartAnotherRun() throws Exception {
        // The source is due (never finished), but the lock is held by a run in progress.
        // ShedLock keeps a source's row after unlocking, so take the lock by upserting it. Its columns are
        // timestamps in UTC without a time zone, so the values must be written in UTC too.
        jdbc.update("insert into shedlock (name, lock_until, locked_at, locked_by) "
                + "values ('ingestion:FAKE', timezone('utc', now()) + interval '10 minutes', "
                + "timezone('utc', now()), 'another-instance') "
                + "on conflict (name) do update set lock_until = excluded.lock_until, "
                + "locked_at = excluded.locked_at, locked_by = excluded.locked_by");
        try {
            awaitAll(scheduler.tick(Instant.now()));

            assertThat(runCount(FAKE)).isZero();
        } finally {
            jdbc.update("update shedlock set lock_until = timezone('utc', now()) - interval '1 second' "
                    + "where name = 'ingestion:FAKE'");
        }
    }

    private static void awaitAll(List<Future<?>> runs) throws Exception {
        for (Future<?> run : runs) {
            run.get(30, TimeUnit.SECONDS);
        }
    }
}
