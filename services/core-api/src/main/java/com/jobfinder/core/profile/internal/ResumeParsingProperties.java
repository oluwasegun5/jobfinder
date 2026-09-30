package com.jobfinder.core.profile.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CV parsing pipeline ({@code app.resumes.parsing.*}). A transient failure (ai-service down, LLM
 * overloaded, storage hiccup) is retried up to {@code maxAttempts} times with exponential backoff
 * starting at {@code initialBackoff}; after that the resume is marked FAILED.
 */
@ConfigurationProperties("app.resumes.parsing")
record ResumeParsingProperties(
        @DefaultValue("resumes.parse") String queue,
        @DefaultValue("resumes.parse.dlq") String deadLetterQueue,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2s") Duration initialBackoff) {

    ResumeParsingProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.resumes.parsing.max-attempts must be at least 1");
        }
    }
}
