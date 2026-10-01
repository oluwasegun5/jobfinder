package com.jobfinder.core.ingestion;

import java.time.Instant;
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
 * harmless.
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
}
