package com.jobfinder.core.ingestion.internal.aggregator;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;
import com.jobfinder.core.ingestion.SourceFetchException;
import com.jobfinder.core.ingestion.SourceKind;

class AdzunaAdapterTests extends AggregatorTestSupport {

    private AdzunaAdapter adapter(AggregatorProperties properties) {
        return new AdzunaAdapter(http(properties), properties, JSON);
    }

    private void stubPages() {
        stubJson("/v1/api/jobs/gb/search/1", "adzuna/page-1.json");
        stubJson("/v1/api/jobs/gb/search/2", "adzuna/page-2.json");
    }

    @Test
    void describesItselfAsAnAggregatorThatIsNotAFullListing() {
        AdzunaAdapter adapter = adapter(properties());

        assertThat(adapter.sourceCode()).isEqualTo("ADZUNA");
        assertThat(adapter.kind()).isEqualTo(SourceKind.AGGREGATOR);
        assertThat(adapter.fullListing()).isFalse();
        assertThat(adapter.unavailableReason()).isEmpty();
        assertThat(adapter.attribution().orElseThrow().text()).isEqualTo("Jobs by Adzuna");
        assertThat(adapter.attribution().orElseThrow().url()).isEqualTo("https://www.adzuna.co.uk");
    }

    @Test
    void pagesUntilAShortPageAndSendsTheDocumentedParameters() {
        stubPages();

        List<RawPosting> postings = adapter(properties()).fetch(target("gb:software engineer"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly("4400000001", "4400000002",
                "4400000003");
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/v1/api/jobs/gb/search/1"))
                .withQueryParam("app_id", equalTo(APP_ID)).withQueryParam("app_key", equalTo(APP_KEY))
                .withQueryParam("what", equalTo("software engineer")).withQueryParam("results_per_page", equalTo("2"))
                .withQueryParam("max_days_old", equalTo("30")).withQueryParam("sort_by", equalTo("date")));
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/v1/api/jobs/gb/search/2")));
    }

    @Test
    void thePageCapBoundsTheCostOfATarget() {
        stubPages();
        AggregatorProperties capped = with(properties(), adzuna(APP_ID, APP_KEY, 2, 1, 100), jsearch(RAPID_KEY, 1, 10));

        List<RawPosting> postings = adapter(capped).fetch(target("gb:software engineer"), null).toList();

        assertThat(postings).hasSize(2);
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/v1/api/jobs/gb/search/1")));
        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/v1/api/jobs/gb/search/2")));
    }

    @Test
    void aSearchWithSpecialCharactersIsEncodedAndAWhereIsSent() {
        wiremock.stubFor(get(urlPathEqualTo("/v1/api/jobs/za/search/1")).willReturn(okJson("{\"results\":[]}")));

        adapter(properties()).fetch(target("za:c++ developer:Cape Town"), null).toList();

        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/v1/api/jobs/za/search/1"))
                .withQueryParam("what", equalTo("c++ developer")).withQueryParam("where", equalTo("Cape Town")));
    }

    @Test
    void mapsAnAdWithAnAdvertisedSalary() {
        stubPages();
        AdzunaAdapter adapter = adapter(properties());
        RawPosting posting = adapter.fetch(target("gb:software engineer"), null).toList().get(0);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("gb:software engineer")).orElseThrow();

        assertThat(input.title()).isEqualTo("Senior <strong>Software</strong> <strong>Engineer</strong>");
        assertThat(input.companyName()).isEqualTo("Northwind Labs");
        assertThat(input.locationText()).isEqualTo("Manchester, Greater Manchester, GB");
        assertThat(input.employmentType()).isEqualTo("full_time");
        assertThat(input.applyUrl()).isEqualTo("https://www.adzuna.example/land/ad/4400000001?se=synthetic&v=1");
        assertThat(input.postedAt()).isEqualTo(Instant.parse("2026-09-28T08:15:00Z"));
        assertThat(input.salaryMin()).isEqualByComparingTo(new BigDecimal("65000"));
        assertThat(input.salaryMax()).isEqualByComparingTo(new BigDecimal("80000"));
        assertThat(input.salaryCurrency()).isEqualTo("GBP");
        assertThat(input.salaryPeriod()).isNull();
    }

    @Test
    void dropsAnAdzunaSalaryEstimateAndReadsContractAndPartTime() {
        stubPages();
        AdzunaAdapter adapter = adapter(properties());
        List<RawPosting> postings = adapter.fetch(target("gb:software engineer"), null).toList();

        NormalizerInput predicted = adapter.toNormalizerInput(postings.get(1), target("gb:software engineer")).orElseThrow();
        NormalizerInput partTime = adapter.toNormalizerInput(postings.get(2), target("gb:software engineer")).orElseThrow();

        assertThat(predicted.salaryMin()).isNull();
        assertThat(predicted.salaryMax()).isNull();
        assertThat(predicted.employmentType()).isEqualTo("contract");
        assertThat(partTime.employmentType()).isEqualTo("part_time");
        assertThat(partTime.locationText()).isEqualTo("Leeds, West Yorkshire, GB");
    }

    @Test
    void anAdWithoutAnyPredictionFlagKeepsNoSalary() {
        AdzunaAdapter adapter = adapter(properties());
        RawPosting posting = new RawPosting("1", "{\"id\":1,\"title\":\"Dev\",\"salary_min\":50000,\"salary_max\":60000}");

        NormalizerInput input = adapter.toNormalizerInput(posting, target("us:dev")).orElseThrow();

        assertThat(input.salaryMin()).isNull();
    }

    @Test
    void withoutKeysTheSourceIsUnavailableAndMakesNoRequest() {
        AggregatorProperties keyless = with(properties(), adzuna("", null, 2, 2, 100), jsearch(RAPID_KEY, 1, 10));
        AdzunaAdapter adapter = adapter(keyless);

        assertThat(adapter.unavailableReason()).hasValueSatisfying(
                reason -> assertThat(reason).contains("ADZUNA_APP_ID", "ADZUNA_APP_KEY"));
        Throwable thrown = catchThrowable(() -> adapter.fetch(target("gb:software engineer"), null));
        assertThat(thrown).isInstanceOf(SourceFetchException.class);
        assertThat(((SourceFetchException) thrown).retryable()).isFalse();
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }

    @Test
    void aTargetThatIsNotASearchIsAPermanentFailureWithoutARequest() {
        AdzunaAdapter adapter = adapter(properties());

        for (String identifier : new String[] { "", "GB:dev", "gbr:dev", "gb software", "12:dev", ":dev" }) {
            Throwable thrown = catchThrowable(() -> adapter.fetch(target(identifier), null));
            assertThat(thrown).isInstanceOf(SourceFetchException.class);
            assertThat(((SourceFetchException) thrown).retryable()).isFalse();
        }
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }

    @Test
    void theDailyBudgetStopsPagingAndThenFailsTheTargetWithTheReason() {
        stubPages();
        AggregatorProperties oneRequest = with(properties(), adzuna(APP_ID, APP_KEY, 2, 2, 1), jsearch(RAPID_KEY, 1, 10));
        AdzunaAdapter adapter = adapter(oneRequest);

        List<RawPosting> first = adapter.fetch(target("gb:software engineer"), null).toList();
        Throwable second = catchThrowable(() -> adapter.fetch(target("gb:software engineer"), null));

        assertThat(first).hasSize(2);
        assertThat(second).isInstanceOf(SourceFetchException.class).hasMessageContaining("daily request budget (1)");
        assertThat(((SourceFetchException) second).retryable()).isFalse();
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/v1/api/jobs/gb/search/1")));
        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/v1/api/jobs/gb/search/2")));
    }
}
