package com.jobfinder.core.interview.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.interview.mock.*} (docs/adr/0034-mock-interview.md): the hard per-session limits and timings of the mock
 * interview.
 *
 * @param promptVersion   sent to ai-service with every request and recorded with every metered call
 * @param maxTurns        the most questions a session may have (a request may ask for fewer, never for more)
 * @param maxAnswerChars  the longest answer accepted; a longer one is a 400 and calls no model
 * @param abandonAfter    an ACTIVE session with no activity for this long becomes ABANDONED and cannot be resumed
 * @param inFlightTimeout a request that took the session's single in-flight slot and never gave it back (a crash) is
 *                        replaced after this; it must be longer than a turn call plus a summary call
 * @param readTimeout     one ai-service call
 */
@ConfigurationProperties("app.interview.mock")
record MockInterviewProperties(
        @DefaultValue("mock_interview/v1") String promptVersion,
        @DefaultValue("8") int maxTurns,
        @DefaultValue("4000") int maxAnswerChars,
        @DefaultValue("24h") Duration abandonAfter,
        @DefaultValue("4m") Duration inFlightTimeout,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("90s") Duration readTimeout) {

    MockInterviewProperties {
        if (promptVersion == null || !promptVersion.matches("mock_interview/v[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException(
                    "app.interview.mock.prompt-version must look like mock_interview/v1");
        }
        if (maxTurns < 1 || maxTurns > 20) {
            throw new IllegalArgumentException("app.interview.mock.max-turns must be between 1 and 20");
        }
        if (maxAnswerChars < 50 || maxAnswerChars > 8000) {
            throw new IllegalArgumentException("app.interview.mock.max-answer-chars must be between 50 and 8000");
        }
        if (abandonAfter == null || abandonAfter.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("app.interview.mock.abandon-after must be at least 1m");
        }
        if (inFlightTimeout == null || inFlightTimeout.compareTo(readTimeout.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "app.interview.mock.in-flight-timeout must cover two ai-service calls (2 x read-timeout)");
        }
    }
}
