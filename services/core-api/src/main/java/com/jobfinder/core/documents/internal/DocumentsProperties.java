package com.jobfinder.core.documents.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code app.documents.*} (docs/adr/0029-resume-tailoring.md). */
@ConfigurationProperties("app.documents")
record DocumentsProperties(
        /** The tailoring prompt, sent to ai-service with every request and stored with every draft. */
        @DefaultValue("tailor_resume/v1") String promptVersion,
        @DefaultValue Tailoring tailoring,
        @DefaultValue FactCheckCall factCheck) {

    DocumentsProperties {
        if (promptVersion == null || !promptVersion.matches("tailor_resume/v[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException("app.documents.prompt-version must look like tailor_resume/v1");
        }
    }

    /**
     * @param descriptionChars  the job description is cut to this length before it is sent (ai-service cuts it
     *                          again to its own, smaller budget)
     * @param generationTimeout a GENERATING draft older than this is treated as abandoned (its request died) and
     *                          replaced by the next tailoring request
     */
    record Tailoring(
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("120s") Duration readTimeout,
            @DefaultValue("20000") int descriptionChars,
            @DefaultValue("5m") Duration generationTimeout) {
    }

    /** The fact check is deterministic and fast; a slow answer means ai-service is unwell. */
    record FactCheckCall(
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("15s") Duration readTimeout) {
    }
}
