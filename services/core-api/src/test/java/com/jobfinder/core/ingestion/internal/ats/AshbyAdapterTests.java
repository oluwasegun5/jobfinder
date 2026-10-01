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

class AshbyAdapterTests extends AtsTestSupport {

    private final AshbyAdapter adapter = new AshbyAdapter(http(properties()), properties(), JSON);

    @Test
    void fetchesListedJobsOnlyWithCompensation() {
        stubJson("/posting-api/job-board/exampleai", "ashby/board.json");

        List<RawPosting> postings = adapter.fetch(target("exampleai"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId)
                .containsExactly("a5b00000-0000-4000-8000-000000000001", "a5b00000-0000-4000-8000-000000000003");
        wiremock.verify(getRequestedFor(urlEqualTo("/posting-api/job-board/exampleai?includeCompensation=true")));
    }

    @Test
    void mapsARemotePostingWithSalary() {
        stubJson("/posting-api/job-board/exampleai", "ashby/board.json");
        RawPosting posting = adapter.fetch(target("exampleai"), null).toList().get(0);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("exampleai")).orElseThrow();

        assertThat(input.title()).isEqualTo("Product Designer");
        assertThat(input.description()).isEqualTo("<p>Design the product.</p>");
        assertThat(input.locationText()).isEqualTo("Remote - Europe");
        assertThat(input.remote()).isTrue();
        assertThat(input.employmentType()).isEqualTo("FullTime");
        assertThat(input.applyUrl()).endsWith("a5b00000-0000-4000-8000-000000000001");
        assertThat(input.postedAt()).isEqualTo(Instant.parse("2026-09-10T10:00:00Z"));
        assertThat(input.salaryMin()).isEqualByComparingTo(new BigDecimal("90000"));
        assertThat(input.salaryMax()).isEqualByComparingTo(new BigDecimal("120000"));
        assertThat(input.salaryCurrency()).isEqualTo("EUR");
        assertThat(input.salaryPeriod()).isEqualTo("1 YEAR");
    }

    @Test
    void mapsAHybridPostingThroughItsLocationText() {
        stubJson("/posting-api/job-board/exampleai", "ashby/board.json");
        RawPosting posting = adapter.fetch(target("exampleai"), null).toList().get(1);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("exampleai")).orElseThrow();

        assertThat(input.locationText()).isEqualTo("London, UK (Hybrid)");
        assertThat(input.remote()).isNull();
        assertThat(input.employmentType()).isEqualTo("PartTime");
        assertThat(input.description()).isEqualTo("Sell the product.");
        assertThat(input.salaryMin()).isNull();
    }
}
