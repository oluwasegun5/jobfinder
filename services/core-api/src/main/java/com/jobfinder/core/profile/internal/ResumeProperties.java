package com.jobfinder.core.profile.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Limits on uploaded CVs ({@code app.resumes.*}). */
@ConfigurationProperties("app.resumes")
record ResumeProperties(
        @DefaultValue("5MB") DataSize maxSize,
        @DefaultValue("10") int maxPerUser,
        @DefaultValue("5m") Duration downloadUrlTtl) {
}
