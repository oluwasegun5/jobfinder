package com.jobfinder.core.ingestion.internal.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;

class LeverAdapterTests extends AtsTestSupport {

    private LeverAdapter adapter(int pageSize) {
        AtsProperties properties = properties(Duration.ofSeconds(5), 33_554_432L, pageSize, 100);
        return new LeverAdapter(http(properties), properties, JSON);
    }

    private void stubPages() {
        wiremock.stubFor(get(urlEqualTo("/v0/postings/examplelabs?mode=json&skip=0&limit=2"))
                .willReturn(okJson(fixture("lever/page-full-1.json"))));
        wiremock.stubFor(get(urlEqualTo("/v0/postings/examplelabs?mode=json&skip=2&limit=2"))
                .willReturn(okJson(fixture("lever/page-full-2.json"))));
    }

    @Test
    void pagesUntilAShortPage() {
        stubPages();

        List<RawPosting> postings = adapter(2).fetch(target("examplelabs"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly(
                "7c1e0000-0000-4000-8000-000000000001", "7c1e0000-0000-4000-8000-000000000002",
                "7c1e0000-0000-4000-8000-000000000003");
        wiremock.verify(2, getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/v0/postings/examplelabs")));
    }

    @Test
    void stopsAfterAnEmptyPageWhenTheLastPageWasFull() {
        wiremock.stubFor(get(urlEqualTo("/v0/postings/examplelabs?mode=json&skip=0&limit=2"))
                .willReturn(okJson(fixture("lever/page-full-1.json"))));
        wiremock.stubFor(get(urlEqualTo("/v0/postings/examplelabs?mode=json&skip=2&limit=2")).willReturn(okJson("[]")));

        assertThat(adapter(2).fetch(target("examplelabs"), null).toList()).hasSize(2);
    }

    @Test
    void mapsAHybridPostingWithSalaryAndRebuiltDescription() {
        stubPages();
        LeverAdapter adapter = adapter(2);
        RawPosting posting = adapter.fetch(target("examplelabs"), null).toList().get(0);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("examplelabs")).orElseThrow();

        assertThat(input.title()).isEqualTo("Staff Data Engineer");
        assertThat(input.companyName()).isNull();
        assertThat(input.locationText()).isEqualTo("Berlin, Germany (Hybrid)");
        assertThat(input.remote()).isNull();
        assertThat(input.employmentType()).isEqualTo("Full-time");
        assertThat(input.description()).contains("Join the data platform team.")
                .contains("<h3>What you will do</h3><ul><li>Design pipelines</li>")
                .contains("We value diverse teams.");
        assertThat(input.applyUrl()).endsWith("7c1e0000-0000-4000-8000-000000000001");
        assertThat(input.postedAt()).isEqualTo(Instant.ofEpochMilli(1788000000000L));
        assertThat(input.salaryMin()).isEqualByComparingTo(new BigDecimal("120000"));
        assertThat(input.salaryCurrency()).isEqualTo("EUR");
        assertThat(input.salaryPeriod()).isEqualTo("per-year-salary");
    }

    @Test
    void mapsRemoteAndOnsiteFlagsAndFallsBackToPlainDescription() {
        stubPages();
        LeverAdapter adapter = adapter(2);
        List<RawPosting> postings = adapter.fetch(target("examplelabs"), null).toList();

        NormalizerInput remote = adapter.toNormalizerInput(postings.get(1), target("examplelabs")).orElseThrow();
        NormalizerInput onsite = adapter.toNormalizerInput(postings.get(2), target("examplelabs")).orElseThrow();

        assertThat(remote.remote()).isTrue();
        assertThat(remote.description()).isEqualTo("Help our customers.");
        assertThat(remote.salaryMin()).isNull();
        assertThat(onsite.remote()).isFalse();
        assertThat(onsite.employmentType()).isEqualTo("Internship");
    }
}
