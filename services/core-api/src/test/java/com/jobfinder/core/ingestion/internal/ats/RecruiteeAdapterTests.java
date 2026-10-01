package com.jobfinder.core.ingestion.internal.ats;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;

class RecruiteeAdapterTests extends AtsTestSupport {

    private final RecruiteeAdapter adapter = new RecruiteeAdapter(http(properties()), properties(), JSON);

    @Test
    void fetchesPublishedOffersOnly() {
        stubJson("/api/offers/", "recruitee/offers.json");

        List<RawPosting> postings = adapter.fetch(target("examplebv"), null).toList();

        assertThat(postings).extracting(RawPosting::externalId).containsExactly("5550001", "5550002");
    }

    @Test
    void mapsAHybridOfferWithSalary() {
        stubJson("/api/offers/", "recruitee/offers.json");
        RawPosting posting = adapter.fetch(target("examplebv"), null).toList().get(0);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("examplebv")).orElseThrow();

        assertThat(input.title()).isEqualTo("Backend Developer");
        assertThat(input.companyName()).isEqualTo("Example BV");
        assertThat(input.description()).isEqualTo("<p>Write APIs.</p><h3>Requirements</h3><ul><li>Java</li></ul>");
        assertThat(input.locationText()).isEqualTo("Amsterdam, Noord-Holland, Netherlands (Hybrid)");
        assertThat(input.remote()).isNull();
        assertThat(input.employmentType()).isEqualTo("fulltime_permanent");
        assertThat(input.applyUrl()).isEqualTo("https://careers.example.test/o/backend-developer");
        assertThat(input.postedAt()).isEqualTo(Instant.parse("2026-09-29T16:02:37Z"));
        assertThat(input.expiresAt()).isNull();
        assertThat(input.salaryMin()).isEqualByComparingTo(new BigDecimal("4000"));
        assertThat(input.salaryMax()).isEqualByComparingTo(new BigDecimal("6000"));
        assertThat(input.salaryCurrency()).isEqualTo("EUR");
        assertThat(input.salaryPeriod()).isEqualTo("month");
    }

    @Test
    void mapsARemoteOfferWithAClosingDate() {
        stubJson("/api/offers/", "recruitee/offers.json");
        RawPosting posting = adapter.fetch(target("examplebv"), null).toList().get(1);

        NormalizerInput input = adapter.toNormalizerInput(posting, target("examplebv")).orElseThrow();

        assertThat(input.remote()).isTrue();
        assertThat(input.locationText()).isEqualTo("Lagos, Nigeria");
        assertThat(input.employmentType()).isEqualTo("parttime_permanent");
        assertThat(input.expiresAt()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
        assertThat(input.salaryMin()).isNull();
    }
}
