package com.jobfinder.core.ingestion;

import java.time.Instant;
import java.util.List;

/**
 * One source as the dashboard shows it. {@code health} is what the last run said (UNKNOWN, HEALTHY, DEGRADED or
 * FAILING). {@code lastRun} is the latest run that has finished; {@code running} says another is in progress.
 * {@code nextDueAt} is when the scheduler will next start the source, in the past if it is already due, and
 * {@code null} when it is not scheduled or has never run (due at once).
 */
public record SourceOverview(String code, SourceKind kind, boolean enabled, SourceSchedule schedule,
        String unavailableReason, String health, Instant lastRunAt, Instant nextDueAt, boolean running,
        int enabledTargets, int totalTargets, IngestionRunView lastRun, List<SourceAlertView> alerts) {
}
