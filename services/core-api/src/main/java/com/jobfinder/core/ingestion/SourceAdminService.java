package com.jobfinder.core.ingestion;

import java.util.List;

/**
 * What the admin dashboard needs from the ingestion module (ADR 0024): the sources with their health and
 * last run, switching a source on or off, starting a run without waiting for it, and the run history.
 */
public interface SourceAdminService {

    /** Whether {@link #startRun} started a run. */
    enum RunStart {
        /** The run was started on a background thread; it is not finished. */
        STARTED,
        /** Another run of the same source (here or on another instance) holds its lock; nothing was started. */
        ALREADY_RUNNING
    }

    /** Every registered source, by code. */
    List<SourceOverview> sources();

    /**
     * Turns the scheduler's interest in a source on or off. The scheduler skips a disabled source from its next
     * tick; a run already in progress is not interrupted, and a manual run of a disabled source still works.
     *
     * @throws UnknownSourceException if no adapter is registered under the code
     */
    SourceOverview setEnabled(String sourceCode, boolean enabled);

    /**
     * Starts a run of the source on a background thread and returns at once, taking the same per-source lock as a
     * scheduled run, so it never overlaps one. Works on a disabled source.
     *
     * @throws UnknownSourceException     if no adapter is registered under the code
     * @throws SourceUnavailableException if the source cannot run (its API key is not configured)
     */
    RunStart startRun(String sourceCode);

    /**
     * Runs, newest first.
     *
     * @param sourceCode only this source's runs, or {@code null} for all of them
     * @param page       zero-based page number
     * @param size       runs per page, 1 to {@link #MAX_PAGE_SIZE}
     * @throws UnknownSourceException if a source code is given and no adapter is registered under it
     */
    IngestionRunPage runs(String sourceCode, int page, int size);

    int MAX_PAGE_SIZE = 100;
}
