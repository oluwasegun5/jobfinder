package com.jobfinder.core.ingestion.internal.aggregator;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.locks.ReentrantLock;

import com.jobfinder.core.ingestion.SourceFetchException;

/**
 * One source's quota guard, shared by all its targets and runs: a hard budget of requests per UTC day and a
 * minimum pause between two requests. The pipeline's rate limiter paces calls to {@code fetch}, not the pages
 * inside one, so a multi-page source needs this to stay under its per-minute limit. State is in memory: a
 * restart gives the day's budget back, which is acceptable because the run schedule is persisted
 * ({@code sources.last_run_at}) and restarts do not re-run a source early.
 */
final class RequestGate {

    private final String source;
    private final int perDay;
    private final long minIntervalNanos;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private LocalDate day;
    private int used;
    private long lastRequestNanos;
    private boolean any;

    RequestGate(String source, int perDay, Duration minInterval, Clock clock) {
        this.source = source;
        this.perDay = perDay;
        this.minIntervalNanos = minInterval.toNanos();
        this.clock = clock;
    }

    /**
     * Takes one request from today's budget, waiting out the minimum interval first. Returns false, without
     * waiting, when the day's budget is spent.
     */
    boolean tryAcquire() {
        lock.lock();
        try {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            if (!today.equals(day)) {
                day = today;
                used = 0;
            }
            if (used >= perDay) {
                return false;
            }
            if (any && minIntervalNanos > 0) {
                long wait = minIntervalNanos - (System.nanoTime() - lastRequestNanos);
                if (wait > 0) {
                    sleep(wait);
                }
            }
            used++;
            any = true;
            lastRequestNanos = System.nanoTime();
            return true;
        } finally {
            lock.unlock();
        }
    }

    int perDay() {
        return perDay;
    }

    private void sleep(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SourceFetchException.transientFailure(source + " request was interrupted", e);
        }
    }
}
