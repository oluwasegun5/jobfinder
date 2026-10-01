package com.jobfinder.core.ingestion.internal.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;

class WorkableAdapterTests extends AtsTestSupport {

    private final WorkableAdapter adapter = new WorkableAdapter(http(properties()), properties(), JSON);

    @Test
    void fetchesTheAccountWithDetailsAndKeepsTheCompanyName() {
        stubJson("/api/v1/widget/accounts/exampleworks", "workable/account.json");

        List<RawPosting> postings = adapter.fetch(target("exampleworks"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly("AAA0000001", "AAA0000002");
        assertThat(postings.get(0).payload()).contains("\"company_name\":\"Example Works\"");
        wiremock.verify(getRequestedFor(urlEqualTo("/api/v1/widget/accounts/exampleworks?details=true")));
    }

    @Test
    void mapsARemotePosting() {
        stubJson("/api/v1/widget/accounts/exampleworks", "workable/account.json");
        RawPosting posting = adapter.fetch(target("exampleworks"), null).toList().get(0);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("exampleworks")).orElseThrow();

        assertThat(input.title()).isEqualTo("QA Engineer");
        assertThat(input.companyName()).isEqualTo("Example Works");
        assertThat(input.description()).isEqualTo("<p>Test our releases.</p>");
        assertThat(input.locationText()).isEqualTo("Accra, Greater Accra, Ghana");
        assertThat(input.remote()).isTrue();
        assertThat(input.employmentType()).isEqualTo("Full-time");
        assertThat(input.applyUrl()).isEqualTo("https://apply.example.test/j/AAA0000001");
        assertThat(input.postedAt()).isEqualTo(Instant.parse("2026-09-05T00:00:00Z"));
    }

    @Test
    void mapsAnOnSitePosting() {
        stubJson("/api/v1/widget/accounts/exampleworks", "workable/account.json");
        RawPosting posting = adapter.fetch(target("exampleworks"), null).toList().get(1);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("exampleworks")).orElseThrow();

        assertThat(input.remote()).isFalse();
        assertThat(input.locationText()).isEqualTo("Nairobi, Kenya");
        assertThat(input.employmentType()).isEqualTo("Part-time");
    }
}
