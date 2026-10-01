package com.jobfinder.core.ingestion.internal.ats;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the ATS adapters ({@code app.ingestion.ats.*}). None of these is a secret: the six public
 * job-board APIs need no key. The base URLs are properties so tests can point them at WireMock;
 * {@code recruiteeBaseUrl} may contain {@code {token}}, because Recruitee puts the company in the host name.
 *
 * <p>
 * {@code smartRecruitersMaxDetails} bounds the one-request-per-posting detail calls SmartRecruiters needs
 * for a description (its list has none); postings beyond the bound are still listed, without a description.
 */
@ConfigurationProperties("app.ingestion.ats")
record AtsProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("33554432") long maxResponseBytes,
        @DefaultValue("https://boards-api.greenhouse.io") String greenhouseBaseUrl,
        @DefaultValue("https://api.lever.co") String leverBaseUrl,
        @DefaultValue("100") int leverPageSize,
        @DefaultValue("https://api.ashbyhq.com") String ashbyBaseUrl,
        @DefaultValue("https://apply.workable.com") String workableBaseUrl,
        @DefaultValue("https://api.smartrecruiters.com") String smartRecruitersBaseUrl,
        @DefaultValue("100") int smartRecruitersMaxDetails,
        @DefaultValue("150ms") Duration smartRecruitersDetailDelay,
        @DefaultValue("https://{token}.recruitee.com") String recruiteeBaseUrl) {

    AtsProperties {
        if (maxResponseBytes < 1024) {
            throw new IllegalArgumentException("app.ingestion.ats.max-response-bytes must be at least 1024");
        }
        if (leverPageSize < 1 || leverPageSize > 1000) {
            throw new IllegalArgumentException("app.ingestion.ats.lever-page-size must be between 1 and 1000");
        }
        if (smartRecruitersMaxDetails < 0) {
            throw new IllegalArgumentException("app.ingestion.ats.smart-recruiters-max-details must not be negative");
        }
    }
}
