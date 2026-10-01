package com.jobfinder.core.ingestion.internal.aggregator;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;
import com.jobfinder.core.ingestion.SourceFetchException;
import com.jobfinder.core.ingestion.SourceKind;

class ArbeitnowAdapterTests extends AggregatorTestSupport {

    private ArbeitnowAdapter adapter(int maxPages) {
        AggregatorProperties base = properties();
        AggregatorProperties properties = new AggregatorProperties(base.connectTimeout(), base.readTimeout(),
                base.maxResponseBytes(), base.adzuna(), base.jsearch(), base.remotive(),
                new AggregatorProperties.Arbeitnow(baseUrl(), maxPages, 100, Duration.ZERO), base.remoteok());
        return new ArbeitnowAdapter(http(properties), properties, JSON);
    }

    private void stubPages() {
        wiremock.stubFor(get(urlPathEqualTo("/api/job-board-api")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson(fixture("arbeitnow/page-1.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/api/job-board-api")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson(fixture("arbeitnow/page-2.json"))));
    }

    @Test
    void describesItselfAsAnAggregatorThatIsNotAFullListing() {
        ArbeitnowAdapter adapter = adapter(3);

        assertThat(adapter.sourceCode()).isEqualTo("ARBEITNOW");
        assertThat(adapter.kind()).isEqualTo(SourceKind.AGGREGATOR);
        assertThat(adapter.fullListing()).isFalse();
        assertThat(adapter.attribution().orElseThrow().url()).isEqualTo("https://www.arbeitnow.com");
    }

    @Test
    void followsThePagesUntilThereIsNoNextLink() {
        stubPages();

        List<RawPosting> postings = adapter(3).fetch(target("all"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly(
                "backend-developer-acme-gmbh-berlin-100001", "devops-engineer-globex-remote-100002",
                "werkstudent-initech-munich-100003");
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/api/job-board-api")));
    }

    @Test
    void thePageCapStopsEvenWhenThereIsANextLink() {
        stubPages();

        assertThat(adapter(1).fetch(target("all"), null).toList()).hasSize(2);
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/api/job-board-api")));
    }

    @Test
    void mapsEpochSecondsTheRemoteFlagAndTheJobType() {
        stubPages();
        ArbeitnowAdapter adapter = adapter(3);
        List<RawPosting> postings = adapter.fetch(target("all"), null).toList();

        NormalizerInput office = adapter.toNormalizerInput(postings.get(0), target("all")).orElseThrow();
        NormalizerInput remote = adapter.toNormalizerInput(postings.get(1), target("all")).orElseThrow();
        NormalizerInput intern = adapter.toNormalizerInput(postings.get(2), target("all")).orElseThrow();

        assertThat(office.title()).isEqualTo("Backend Developer (m/f/d)");
        assertThat(office.companyName()).isEqualTo("Acme GmbH");
        assertThat(office.locationText()).isEqualTo("Berlin");
        assertThat(office.remote()).isFalse();
        assertThat(office.employmentType()).isEqualTo("Full-time");
        assertThat(office.postedAt()).isEqualTo(Instant.ofEpochSecond(1790850000L));
        assertThat(office.applyUrl()).startsWith("https://www.arbeitnow.example/jobs/");
        assertThat(remote.remote()).isTrue();
        assertThat(remote.employmentType()).isNull();
        assertThat(remote.applyUrl()).isEqualTo("https://globex.example/careers/100002");
        assertThat(intern.employmentType()).isEqualTo("Internship");
    }

    @Test
    void onlyAllIsATarget() {
        assertThat(catchThrowable(() -> adapter(3).fetch(target("germany"), null)))
                .isInstanceOf(SourceFetchException.class);
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }
}
