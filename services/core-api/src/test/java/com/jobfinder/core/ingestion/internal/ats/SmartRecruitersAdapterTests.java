package com.jobfinder.core.ingestion.internal.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;

class SmartRecruitersAdapterTests extends AtsTestSupport {

    private SmartRecruitersAdapter adapter(int maxDetails) {
        AtsProperties properties = properties(Duration.ofSeconds(5), 33_554_432L, 100, maxDetails);
        return new SmartRecruitersAdapter(http(properties), properties, JSON);
    }

    private void stubBoard() {
        wiremock.stubFor(get(urlEqualTo("/v1/companies/examplehq/postings?limit=100&offset=0"))
                .willReturn(okJson(fixture("smartrecruiters/list-1.json"))));
        stubJson("/v1/companies/examplehq/postings/7440001", "smartrecruiters/detail-7440001.json");
        stubJson("/v1/companies/examplehq/postings/7440002", "smartrecruiters/detail-7440002.json");
        wiremock.stubFor(get(urlEqualTo("/v1/companies/examplehq/postings/7440003")).willReturn(aResponse().withStatus(404)));
    }

    @Test
    void listsPostingsAndFetchesEachDetail() {
        stubBoard();

        List<RawPosting> postings = adapter(100).fetch(target("examplehq"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly("7440001", "7440002", "7440003");
        assertThat(postings.get(0).payload()).contains("jobAd");
        assertThat(postings.get(2).payload()).doesNotContain("jobAd");
        wiremock.verify(3, getRequestedFor(urlPathMatching("/v1/companies/examplehq/postings/\\d+")));
    }

    @Test
    void boundsTheNumberOfDetailRequests() {
        stubBoard();

        List<RawPosting> postings = adapter(1).fetch(target("examplehq"), null).toList();

        assertThat(postings).hasSize(3);
        assertThat(postings.get(0).payload()).contains("jobAd");
        assertThat(postings.get(1).payload()).doesNotContain("jobAd");
        wiremock.verify(1, getRequestedFor(urlPathMatching("/v1/companies/examplehq/postings/\\d+")));
    }

    @Test
    void mapsAPostingWithItsJobAdSections() {
        stubBoard();
        SmartRecruitersAdapter adapter = adapter(100);
        RawPosting posting = adapter.fetch(target("examplehq"), null).toList().get(0);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("examplehq")).orElseThrow();

        assertThat(input.title()).isEqualTo("Platform Engineer");
        assertThat(input.companyName()).isEqualTo("Example HQ");
        assertThat(input.description()).contains("<h3>Company Description</h3><p>We build things.</p>")
                .contains("<h3>Job Description</h3><p>Run the platform.</p>").contains("<li>Kubernetes</li>");
        assertThat(input.locationText()).isEqualTo("Lisbon, Lisboa, Portugal (Hybrid)");
        assertThat(input.remote()).isNull();
        assertThat(input.employmentType()).isEqualTo("Full-time");
        assertThat(input.applyUrl()).isEqualTo("https://jobs.example.test/examplehq/7440001-platform-engineer");
        assertThat(input.postedAt()).isEqualTo(Instant.parse("2026-09-09T09:43:26.403Z"));
    }

    @Test
    void mapsListOnlyPostingsWithoutDescription() {
        stubBoard();
        SmartRecruitersAdapter adapter = adapter(100);
        List<RawPosting> postings = adapter.fetch(target("examplehq"), null).toList();

        NormalizerInput remote = adapter.toNormalizerInput(postings.get(1), target("examplehq")).orElseThrow();
        NormalizerInput gone = adapter.toNormalizerInput(postings.get(2), target("examplehq")).orElseThrow();

        assertThat(remote.remote()).isTrue();
        assertThat(remote.employmentType()).isEqualTo("Contract");
        assertThat(gone.description()).isNull();
        assertThat(gone.remote()).isFalse();
        assertThat(gone.locationText()).isEqualTo("Cape Town, South Africa");
        assertThat(gone.applyUrl()).isNull();
    }

    @Test
    void aTransientDetailFailureFailsTheFetch() {
        stubBoard();
        wiremock.stubFor(get(urlEqualTo("/v1/companies/examplehq/postings/7440002")).willReturn(aResponse().withStatus(429)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter(100).fetch(target("examplehq"), null))
                .isInstanceOfSatisfying(com.jobfinder.core.ingestion.SourceFetchException.class,
                        e -> assertThat(e.retryable()).isTrue());
    }
}
