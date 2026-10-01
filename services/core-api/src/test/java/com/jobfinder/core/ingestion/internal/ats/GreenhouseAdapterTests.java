package com.jobfinder.core.ingestion.internal.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;

class GreenhouseAdapterTests extends AtsTestSupport {

    private final GreenhouseAdapter adapter = new GreenhouseAdapter(http(properties()), properties(), JSON);

    @Test
    void fetchesEveryJobOfTheBoardWithContent() {
        stubJson("/v1/boards/examplecorp/jobs", "greenhouse/board.json");

        List<RawPosting> postings = adapter.fetch(target("examplecorp"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly("4001001", "4001002");
        assertThat(postings.get(0).payload()).contains("Senior Backend Engineer");
        wiremock.verify(getRequestedFor(urlEqualTo("/v1/boards/examplecorp/jobs?content=true")));
    }

    @Test
    void mapsAPostingWithPayRange() {
        stubJson("/v1/boards/examplecorp/jobs", "greenhouse/board.json");
        RawPosting posting = adapter.fetch(target("examplecorp"), null).toList().get(0);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("examplecorp")).orElseThrow();

        assertThat(input.title()).isEqualTo("Senior Backend Engineer");
        assertThat(input.companyName()).isEqualTo("Example Corp");
        assertThat(input.description()).startsWith("&lt;p&gt;Build services in");
        assertThat(input.locationText()).isEqualTo("Remote - Nigeria");
        assertThat(input.applyUrl()).isEqualTo("https://boards.example.test/examplecorp/jobs/4001001");
        assertThat(input.postedAt()).isEqualTo(Instant.parse("2026-09-01T13:30:00Z"));
        assertThat(input.salaryMin()).isEqualByComparingTo(new BigDecimal("90000"));
        assertThat(input.salaryMax()).isEqualByComparingTo(new BigDecimal("120000"));
        assertThat(input.salaryCurrency()).isEqualTo("USD");
        assertThat(input.salaryPeriod()).isNull();
    }

    @Test
    void mapsAPostingWithoutCompanyOrPay() {
        stubJson("/v1/boards/examplecorp/jobs", "greenhouse/board.json");
        RawPosting posting = adapter.fetch(target("examplecorp"), null).toList().get(1);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("examplecorp")).orElseThrow();

        assertThat(input.companyName()).isNull();
        assertThat(input.locationText()).isEqualTo("Lagos, Nigeria");
        assertThat(input.salaryMin()).isNull();
        assertThat(input.salaryCurrency()).isNull();
        assertThat(input.postedAt()).isEqualTo(Instant.parse("2026-08-15T08:00:00Z"));
    }

    @Test
    void anEmptyBoardIsAnEmptyListing() {
        wiremock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/v1/boards/empty/jobs?content=true"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{\"jobs\":[],\"meta\":{\"total\":0}}")));

        assertThat(adapter.fetch(target("empty"), null).toList()).isEmpty();
    }

    @Test
    void aPostingWithoutTitleIsRejected() {
        RawPosting posting = new RawPosting("1", "{\"id\":1,\"location\":{\"name\":\"Lagos\"}}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.toNormalizerInput(posting, target("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
