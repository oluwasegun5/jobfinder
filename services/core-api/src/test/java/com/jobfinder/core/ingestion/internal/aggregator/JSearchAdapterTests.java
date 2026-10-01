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

class JSearchAdapterTests extends AggregatorTestSupport {

    private static final String QUERY = "ng:software developer jobs in Nigeria";

    private JSearchAdapter adapter(AggregatorProperties properties) {
        return new JSearchAdapter(http(properties), properties, JSON);
    }

    private void stubPages() {
        wiremock.stubFor(get(urlPathEqualTo("/search")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson(fixture("jsearch/search-page-1.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/search")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson(fixture("jsearch/search-page-2.json"))));
    }

    @Test
    void describesItselfAsAnAggregatorThatIsNotAFullListing() {
        JSearchAdapter adapter = adapter(properties());

        assertThat(adapter.sourceCode()).isEqualTo("JSEARCH");
        assertThat(adapter.kind()).isEqualTo(SourceKind.AGGREGATOR);
        assertThat(adapter.fullListing()).isFalse();
        assertThat(adapter.unavailableReason()).isEmpty();
        assertThat(adapter.attribution()).isPresent();
    }

    @Test
    void sendsTheRapidApiHeadersAndPagesOneRequestAtATime() {
        stubPages();

        List<RawPosting> postings = adapter(properties()).fetch(target(QUERY), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly("synthetic-job-id-0001==",
                "synthetic-job-id-0002==", "synthetic-job-id-0003==");
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/search")).withHeader("X-RapidAPI-Key", equalTo(RAPID_KEY))
                .withHeader("X-RapidAPI-Host", equalTo("jsearch.test"))
                .withQueryParam("query", equalTo("software developer jobs in Nigeria"))
                .withQueryParam("page", equalTo("1")).withQueryParam("num_pages", equalTo("1"))
                .withQueryParam("date_posted", equalTo("month")).withQueryParam("country", equalTo("ng")));
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/search")).withQueryParam("page", equalTo("2")));
    }

    @Test
    void stopsOnAnEmptyPageAndHonoursThePageCap() {
        wiremock.stubFor(get(urlPathEqualTo("/search")).willReturn(okJson("{\"status\":\"OK\",\"data\":[]}")));

        assertThat(adapter(properties()).fetch(target(QUERY), null).toList()).isEmpty();
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/search")));

        wiremock.resetAll();
        stubPages();
        AggregatorProperties onePage = with(properties(), adzuna(APP_ID, APP_KEY, 2, 1, 10), jsearch(RAPID_KEY, 1, 10));
        assertThat(adapter(onePage).fetch(target(QUERY), null).toList()).hasSize(2);
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void mapsAPostingWithLocationPartsSalaryAndExpiry() {
        stubPages();
        JSearchAdapter adapter = adapter(properties());
        RawPosting posting = adapter.fetch(target(QUERY), null).toList().get(0);

        NormalizerInput input = adapter.toNormalizerInput(posting, target(QUERY)).orElseThrow();

        assertThat(input.title()).isEqualTo("Backend Developer");
        assertThat(input.companyName()).isEqualTo("Tailspin Africa");
        assertThat(input.locationText()).isEqualTo("Lagos, LA, NG");
        assertThat(input.remote()).isFalse();
        assertThat(input.employmentType()).isEqualTo("Full-time");
        assertThat(input.applyUrl()).isEqualTo("https://publisher.example/jobs/view/0001");
        assertThat(input.postedAt()).isEqualTo(Instant.parse("2026-09-27T10:26:40Z"));
        assertThat(input.expiresAt()).isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
        assertThat(input.salaryMin()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(input.salaryCurrency()).isEqualTo("USD");
        assertThat(input.salaryPeriod()).isEqualTo("MONTH");
        // The publisher stays in the raw posting, for whoever shows it.
        assertThat(posting.payload()).contains("\"job_publisher\":\"LinkedIn\"");
    }

    @Test
    void mapsRemoteContractAndInternPostingsAndFallsBackToTheTimestamp() {
        stubPages();
        JSearchAdapter adapter = adapter(properties());
        List<RawPosting> postings = adapter.fetch(target(QUERY), null).toList();

        NormalizerInput remote = adapter.toNormalizerInput(postings.get(1), target(QUERY)).orElseThrow();
        NormalizerInput intern = adapter.toNormalizerInput(postings.get(2), target(QUERY)).orElseThrow();

        assertThat(remote.remote()).isTrue();
        assertThat(remote.locationText()).isEqualTo("Remote, Nigeria");
        assertThat(remote.employmentType()).isEqualTo("Contract");
        assertThat(remote.postedAt()).isEqualTo(Instant.ofEpochSecond(1790400000L));
        assertThat(remote.salaryMin()).isNull();
        assertThat(intern.employmentType()).isEqualTo("Internship");
        assertThat(intern.locationText()).isEqualTo("Abuja, NG");
    }

    @Test
    void withoutAKeyTheSourceIsUnavailableAndMakesNoRequest() {
        AggregatorProperties keyless = with(properties(), adzuna(APP_ID, APP_KEY, 2, 2, 10), jsearch("  ", 1, 10));
        JSearchAdapter adapter = adapter(keyless);

        assertThat(adapter.unavailableReason()).hasValueSatisfying(
                reason -> assertThat(reason).contains("JSEARCH_RAPIDAPI_KEY"));
        assertThat(catchThrowable(() -> adapter.fetch(target(QUERY), null))).isInstanceOf(SourceFetchException.class);
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }

    @Test
    void aTargetWithoutASearchIsRefusedBeforeSpendingARequest() {
        JSearchAdapter adapter = adapter(properties());

        assertThat(catchThrowable(() -> adapter.fetch(target("us"), null))).isInstanceOf(SourceFetchException.class);
        assertThat(catchThrowable(() -> adapter.fetch(target("us:"), null))).isInstanceOf(SourceFetchException.class);
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }

    @Test
    void anErrorStatusInsideA200IsPermanent() {
        wiremock.stubFor(get(urlPathEqualTo("/search")).willReturn(okJson("{\"status\":\"ERROR\",\"data\":[]}")));

        Throwable thrown = catchThrowable(() -> adapter(properties()).fetch(target(QUERY), null));

        assertThat(thrown).isInstanceOf(SourceFetchException.class);
        assertThat(((SourceFetchException) thrown).retryable()).isFalse();
    }
}
