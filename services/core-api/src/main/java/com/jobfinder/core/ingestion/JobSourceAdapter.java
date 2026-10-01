package com.jobfinder.core.ingestion;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One implementation per job source. An adapter only knows how to talk to its source and hand back
 * what it received; scheduling, retries, rate limiting, the circuit breaker, raw storage and run
 * metrics are the pipeline's job. Declare an adapter as a Spring bean and it is registered as a
 * source (and scheduled) automatically.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>{@link #fetch} is called once per enabled target per run, possibly more than once for the
 * same target when a retryable failure is retried, so it must be safe to repeat;
 * <li>a failure is reported by throwing {@link SourceFetchException}, never by returning a partial
 * result as if it were complete;
 * <li>exception messages are stored in {@code ingestion_runs.error_summary} and logged, so they must
 * never contain credentials: report "Adzuna request failed (HTTP 503)", not the request URL with
 * its {@code app_key};
 * <li>{@code since} is the start of the last fully successful run of this source, or {@code null}
 * if there has been none. A source that supports incremental fetching may use it to return only
 * newer postings; one that does not can ignore it, because storing the same posting twice is
 * harmless;
 * <li>{@link #toNormalizerInput} maps one stored posting to the normalizer's input; it is where a
 * source's own field names end, and it may throw {@link IllegalArgumentException} for a posting it
 * cannot read, which rejects that posting without failing the run;
 * <li>an incremental adapter (one that really returns only postings newer than {@code since}) must
 * say so with {@link #fullListing()}: a posting absent from an incremental response is not gone.
 * <li>{@link #unavailableReason} says when the adapter cannot run at all (no API key): the source stays
 * registered, but it is skipped and a manual run is refused with that reason;
 * <li>{@link #attribution} is the credit the source's terms require, stored with the source.
 * </ul>
 */
public interface JobSourceAdapter {

    /** The source's code, e.g. {@code GREENHOUSE}: unique, at most 40 characters. */
    String sourceCode();

    SourceKind kind();

    /**
     * The postings for one target. The stream is read fully and closed by the pipeline.
     */
    Stream<RawPosting> fetch(FetchTarget target, Instant since);

    /**
     * Maps one posting, as this adapter fetched it, to the normalizer's input. The default is empty:
     * the posting is kept raw but produces no job, which is only right for a source not yet wired to
     * the normalizer.
     */
    default Optional<NormalizerInput> toNormalizerInput(RawPosting posting, FetchTarget target) {
        return Optional.empty();
    }

    /**
     * Whether each fetch returns everything the target currently lists. Only then does a posting's
     * absence count towards expiry. An adapter that uses {@code since} to return only new postings
     * must return false.
     */
    default boolean fullListing() {
        return true;
    }

    /**
     * Why this adapter cannot run right now, or empty when it can. Used for an aggregator whose API key is
     * not configured: the source stays registered (so the key can be added and the application restarted),
     * but the scheduler skips it and {@link IngestionService#runNow} refuses with this reason. The reason
     * must never contain a credential.
     */
    default Optional<String> unavailableReason() {
        return Optional.empty();
    }

    /** The credit this source's terms require wherever its jobs are shown; empty when it asks for none. */
    default Optional<SourceAttribution> attribution() {
        return Optional.empty();
    }
}
