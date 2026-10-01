package com.jobfinder.core.ingestion;

import java.time.Instant;

/**
 * An alert that is open on a source. {@code rule} is ZERO_JOBS or ERROR_RATE; {@code since} is when it opened,
 * {@code occurrences} how many runs have been in breach since, and {@code lastNotifiedAt} when it was last announced
 * (emailed, or logged when no recipient is configured).
 */
public record SourceAlertView(String rule, Instant since, Instant lastNotifiedAt, int occurrences, String detail) {
}
