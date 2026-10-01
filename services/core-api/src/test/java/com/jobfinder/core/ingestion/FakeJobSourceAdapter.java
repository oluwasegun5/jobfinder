package com.jobfinder.core.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A programmable source for pipeline tests: per target identifier it can return N postings, fail
 * permanently, fail retryably a number of times before succeeding, or block until released. Every
 * call is recorded so tests can see how often, and with which {@code since}, it was called.
 */
public class FakeJobSourceAdapter implements JobSourceAdapter {

    public record Call(String identifier, Instant since) {
    }

    private interface Behaviour {
        Stream<RawPosting> fetch(FetchTarget target, Instant since);
    }

    private final String code;
    private final SourceKind kind;
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int payloadVersion = 1;

    public FakeJobSourceAdapter(String code, SourceKind kind) {
        this.code = code;
        this.kind = kind;
    }

    @Override
    public String sourceCode() {
        return code;
    }

    @Override
    public SourceKind kind() {
        return kind;
    }

    @Override
    public Stream<RawPosting> fetch(FetchTarget target, Instant since) {
        calls.add(new Call(target.identifier(), since));
        Behaviour behaviour = behaviours.get(target.identifier());
        if (behaviour == null) {
            return Stream.empty();
        }
        return behaviour.fetch(target, since);
    }

    /** The target returns {@code count} postings, with ids {@code <identifier>-0 ... <identifier>-(count-1)}. */
    public FakeJobSourceAdapter postings(String identifier, int count) {
        behaviours.put(identifier, (target, since) -> IntStream.range(0, count)
                .mapToObj(i -> posting(identifier + "-" + i)));
        return this;
    }

    public FakeJobSourceAdapter failPermanently(String identifier) {
        behaviours.put(identifier, (target, since) -> {
            throw SourceFetchException.permanentFailure("board " + identifier + " not found (HTTP 404)", null);
        });
        return this;
    }

    public FakeJobSourceAdapter failRetryablyAlways(String identifier) {
        behaviours.put(identifier, (target, since) -> {
            throw SourceFetchException.transientFailure("source unavailable (HTTP 503)", null);
        });
        return this;
    }

    /** The first {@code failures} calls fail retryably; after that the target returns {@code count} postings. */
    public FakeJobSourceAdapter failRetryablyThenPostings(String identifier, int failures, int count) {
        AtomicInteger remaining = new AtomicInteger(failures);
        behaviours.put(identifier, (target, since) -> {
            if (remaining.getAndDecrement() > 0) {
                throw SourceFetchException.transientFailure("timeout", null);
            }
            return IntStream.range(0, count).mapToObj(i -> posting(identifier + "-" + i));
        });
        return this;
    }

    /** The target signals {@code entered}, waits for {@code release}, then returns one posting. */
    public FakeJobSourceAdapter blockUntilReleased(String identifier, CountDownLatch entered, CountDownLatch release) {
        behaviours.put(identifier, (target, since) -> {
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw SourceFetchException.permanentFailure("test never released the fetch", null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw SourceFetchException.permanentFailure("interrupted", e);
            }
            return Stream.of(posting(identifier + "-0"));
        });
        return this;
    }

    /** Changes what subsequent payloads say, to tell a refreshed posting from an untouched one. */
    public FakeJobSourceAdapter payloadVersion(int version) {
        this.payloadVersion = version;
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public long callsFor(String identifier) {
        return calls.stream().filter(c -> c.identifier().equals(identifier)).count();
    }

    public void reset() {
        behaviours.clear();
        calls.clear();
        payloadVersion = 1;
    }

    private RawPosting posting(String externalId) {
        return new RawPosting(externalId,
                "{\"id\":\"%s\",\"title\":\"Job %s\",\"v\":%d}".formatted(externalId, externalId, payloadVersion));
    }
}
