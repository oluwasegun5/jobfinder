package com.jobfinder.core.ingestion.internal.aggregator;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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

class RemoteOkAdapterTests extends AggregatorTestSupport {

    private RemoteOkAdapter adapter() {
        AggregatorProperties properties = properties();
        return new RemoteOkAdapter(http(properties), properties, JSON);
    }

    @Test
    void describesItselfAndCarriesItsAttributionRules() {
        RemoteOkAdapter adapter = adapter();

        assertThat(adapter.sourceCode()).isEqualTo("REMOTEOK");
        assertThat(adapter.kind()).isEqualTo(SourceKind.AGGREGATOR);
        assertThat(adapter.fullListing()).isFalse();
        assertThat(adapter.attribution().orElseThrow().notes()).contains("followed link", "nofollow");
    }

    @Test
    void skipsTheLegalNoticeAtTheStartOfTheArray() {
        stubJson("/api", "remoteok/api.json");

        List<RawPosting> postings = adapter().fetch(target("all"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly("3000001", "3000002");
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api")));
    }

    @Test
    void mapsARemoteJobWithAnnualUsdSalaryAndNoneWhenItIsZero() {
        stubJson("/api", "remoteok/api.json");
        RemoteOkAdapter adapter = adapter();
        List<RawPosting> postings = adapter.fetch(target("all"), null).toList();

        NormalizerInput paid = adapter.toNormalizerInput(postings.get(0), target("all")).orElseThrow();
        NormalizerInput unpaid = adapter.toNormalizerInput(postings.get(1), target("all")).orElseThrow();

        assertThat(paid.title()).isEqualTo("Senior Java Engineer");
        assertThat(paid.companyName()).isEqualTo("Hooli");
        assertThat(paid.remote()).isTrue();
        assertThat(paid.locationText()).isEqualTo("Worldwide");
        assertThat(paid.postedAt()).isEqualTo(Instant.ofEpochSecond(1790438426L));
        assertThat(paid.applyUrl()).isEqualTo(
                "https://remoteok.example/remote-jobs/remote-senior-java-engineer-hooli-3000001");
        assertThat(paid.salaryMin()).isEqualByComparingTo(new BigDecimal("120000"));
        assertThat(paid.salaryMax()).isEqualByComparingTo(new BigDecimal("160000"));
        assertThat(paid.salaryCurrency()).isEqualTo("USD");
        assertThat(paid.salaryPeriod()).isEqualTo("year");
        assertThat(unpaid.salaryMin()).isNull();
        assertThat(unpaid.salaryMax()).isNull();
        assertThat(unpaid.locationText()).isNull();
    }

    @Test
    void onlyAllIsATargetAndOtherIdentifiersAreRefusedWithoutARequest() {
        assertThat(catchThrowable(() -> adapter().fetch(target("java"), null))).isInstanceOf(SourceFetchException.class);
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }
}
