package com.jobfinder.core.ingestion.internal.aggregator;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;
import com.jobfinder.core.ingestion.SourceFetchException;
import com.jobfinder.core.ingestion.SourceKind;

class RemotiveAdapterTests extends AggregatorTestSupport {

    private RemotiveAdapter adapter() {
        AggregatorProperties properties = properties();
        return new RemotiveAdapter(http(properties), properties, JSON);
    }

    @Test
    void describesItselfAndCarriesItsAttributionRules() {
        RemotiveAdapter adapter = adapter();

        assertThat(adapter.sourceCode()).isEqualTo("REMOTIVE");
        assertThat(adapter.kind()).isEqualTo(SourceKind.AGGREGATOR);
        assertThat(adapter.fullListing()).isFalse();
        assertThat(adapter.attribution().orElseThrow().notes()).contains("Remotive URL", "Do not submit");
    }

    @Test
    void fetchesTheWholeFeedInOneRequestWithNoCategory() {
        stubJson("/api/remote-jobs", "remotive/all.json");

        List<RawPosting> postings = adapter().fetch(target("all"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly("2000001", "2000002");
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/remote-jobs")));
    }

    @Test
    void asksForACategoryWhenTheTargetNamesOne() {
        stubJson("/api/remote-jobs", "remotive/all.json");

        adapter().fetch(target("software-dev"), null).toList();

        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/api/remote-jobs"))
                .withQueryParam("category", equalTo("software-dev")));
    }

    @Test
    void mapsAJobWithItsOwnUrlAsTheApplyLink() {
        stubJson("/api/remote-jobs", "remotive/all.json");
        RemotiveAdapter adapter = adapter();
        List<RawPosting> postings = adapter.fetch(target("all"), null).toList();

        NormalizerInput full = adapter.toNormalizerInput(postings.get(0), target("all")).orElseThrow();
        NormalizerInput freelance = adapter.toNormalizerInput(postings.get(1), target("all")).orElseThrow();

        assertThat(full.title()).isEqualTo("Senior Backend Engineer");
        assertThat(full.companyName()).isEqualTo("Litware");
        assertThat(full.remote()).isTrue();
        assertThat(full.locationText()).isEqualTo("Worldwide");
        assertThat(full.employmentType()).isEqualTo("full_time");
        assertThat(full.salaryText()).isEqualTo("$90k - $120k");
        assertThat(full.applyUrl()).isEqualTo(
                "https://remotive.example/remote-jobs/software-dev/senior-backend-engineer-2000001");
        assertThat(full.postedAt()).isEqualTo(Instant.parse("2026-09-21T12:55:11Z"));
        assertThat(freelance.employmentType()).isEqualTo("freelance");
        assertThat(freelance.salaryText()).isNull();
    }

    @Test
    void aCategoryThatIsNotASlugIsRefusedWithoutARequest() {
        RemotiveAdapter adapter = adapter();

        for (String identifier : new String[] { "Software Dev", "a/b", "x&y=1", "", "-x" }) {
            assertThat(catchThrowable(() -> adapter.fetch(target(identifier), null)))
                    .isInstanceOf(SourceFetchException.class);
        }
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }
}
