package com.jobfinder.core.interview.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.interview.*} (docs/adr/0033-interview-prep.md).
 *
 * @param promptVersion     sent to ai-service with every request, recorded with every metered call and part of the
 *                          idempotency key: a new prompt version makes a new prep for the same job
 * @param questionCount     how many questions to ask ai-service for
 * @param descriptionChars  the job description is cut to this length before it is sent (ai-service cuts it again)
 * @param readTimeout       two model calls, one after the other
 * @param generationTimeout a GENERATING placeholder older than this belongs to a request that died and is replaced
 * @param aiDeadline        the overall deadline ai-service puts on one prep request (its INTERVIEW_PREP_DEADLINE_SECONDS,
 *                          mirrored here): when it runs out ai-service answers with the usage of the calls it made, which
 *                          only reaches us if we are still waiting, so it must be at least 5s below {@code readTimeout}
 */
@ConfigurationProperties("app.interview")
record InterviewProperties(
        @DefaultValue("interview/v1") String promptVersion,
        @DefaultValue("12") int questionCount,
        @DefaultValue("20000") int descriptionChars,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("150s") Duration readTimeout,
        @DefaultValue("5m") Duration generationTimeout,
        @DefaultValue("120s") Duration aiDeadline) {

    /** How far below the read timeout the ai-service deadline must be, for its error response to arrive. */
    static final Duration AI_DEADLINE_MARGIN = Duration.ofSeconds(5);

    InterviewProperties {
        if (promptVersion == null || !promptVersion.matches("interview/v[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException("app.interview.prompt-version must look like interview/v1");
        }
        if (questionCount < 6 || questionCount > 20) {
            throw new IllegalArgumentException("app.interview.question-count must be between 6 and 20");
        }
        if (descriptionChars < 500) {
            throw new IllegalArgumentException("app.interview.description-chars must be at least 500");
        }
        if (aiDeadline == null || aiDeadline.plus(AI_DEADLINE_MARGIN).compareTo(readTimeout) > 0) {
            throw new IllegalArgumentException("app.interview.ai-deadline (" + aiDeadline + ") must be at least "
                    + AI_DEADLINE_MARGIN + " below app.interview.read-timeout (" + readTimeout
                    + "), or core-api gives up before ai-service reports the usage of a request that ran out of time");
        }
    }
}
