package com.jobfinder.core.ingestion;

import java.util.Optional;

/** The ingestion module's public entry point. The scheduler and the admin dashboard both use it. */
public interface IngestionService {

    /**
     * Runs one source now and waits for it to finish. Works whether or not the source is enabled:
     * "enabled" decides what the scheduler picks up, not what an admin may trigger by hand.
     *
     * @return the run's summary, or empty if another run of the same source (on this or another
     *         instance) is already in progress, in which case nothing was started
     * @throws UnknownSourceException if no adapter is registered under the code
     */
    Optional<IngestionRunSummary> runNow(String sourceCode);
}
