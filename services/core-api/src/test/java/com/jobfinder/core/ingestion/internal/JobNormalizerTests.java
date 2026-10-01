package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.internal.NormalizedJob.EmploymentType;
import com.jobfinder.core.ingestion.internal.NormalizedJob.SalaryPeriod;
import com.jobfinder.core.ingestion.internal.NormalizedJob.Seniority;
import com.jobfinder.core.ingestion.internal.NormalizedJob.WorkMode;

/** Whole postings, messy the way real ones are, through the normalizer. */
class JobNormalizerTests {

    private final JobNormalizer normalizer = new JobNormalizer();

    @Test
    void anAtsPostingWithEscapedHtmlAndDecoratedTitle() {
        NormalizerInput input = NormalizerInput.builder("  Sr. Backend Engineer (m/f/d) &amp; Platform - Remote [REQ-1042] ")
                .companyName("Acme, Inc.")
                .locationText("Remote - US")
                .employmentType("Full-time")
                .description("&lt;p&gt;Build &amp;amp; run our APIs.&lt;/p&gt;&lt;ul&gt;&lt;li&gt;Java&lt;/li&gt;&lt;/ul&gt;"
                        + "&lt;script&gt;steal()&lt;/script&gt;")
                .salaryText("$140k - $170k a year")
                .applyUrl("https://boards.example.com/acme/jobs/1042?gh_src=abc")
                .postedAt(Instant.parse("2026-09-20T08:00:00Z"))
                .build();

        NormalizedJob job = normalizer.normalize(input, null);

        assertThat(job.title()).isEqualTo("Sr. Backend Engineer (m/f/d) & Platform - Remote [REQ-1042]");
        assertThat(job.normalizedTitle()).isEqualTo("senior backend engineer and platform");
        assertThat(job.companyName()).isEqualTo("Acme, Inc.");
        assertThat(job.normalizedCompany()).isEqualTo("acme");
        assertThat(job.descriptionText()).isEqualTo("Build & run our APIs.\n\n- Java").doesNotContain("steal");
        assertThat(job.descriptionHtml()).contains("<li>Java</li>").doesNotContain("script");
        assertThat(job.city()).isNull();
        assertThat(job.country()).isEqualTo("US");
        assertThat(job.workMode()).isEqualTo(WorkMode.REMOTE);
        assertThat(job.employmentType()).isEqualTo(EmploymentType.FULL_TIME);
        assertThat(job.seniority()).isEqualTo(Seniority.SENIOR);
        assertThat(job.salaryMin()).isEqualByComparingTo("140000");
        assertThat(job.salaryMax()).isEqualByComparingTo("170000");
        assertThat(job.salaryCurrency()).isEqualTo("USD");
        assertThat(job.salaryPeriod()).isEqualTo(SalaryPeriod.YEAR);
        assertThat(job.applyUrl()).isEqualTo("https://boards.example.com/acme/jobs/1042?gh_src=abc");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-09-20T08:00:00Z"));
        assertThat(job.fingerprint()).hasSize(64);
    }

    @Test
    void anAggregatorPostingFromLagosWithMonthlyNairaPay() {
        NormalizerInput input = NormalizerInput.builder("Contract Data Analyst (6 months)")
                .companyName("Kuda Technologies Ltd")
                .locationText("Lagos (Hybrid)")
                .salary(new BigDecimal("400000"), new BigDecimal("550000"), "NGN", "monthly")
                .description("We need an analyst. Employment type: Contract")
                .build();

        NormalizedJob job = normalizer.normalize(input, null);

        assertThat(job.city()).isEqualTo("Lagos");
        assertThat(job.country()).isEqualTo("NG");
        assertThat(job.workMode()).isEqualTo(WorkMode.HYBRID);
        assertThat(job.employmentType()).isEqualTo(EmploymentType.CONTRACT);
        assertThat(job.seniority()).isNull();
        assertThat(job.salaryCurrency()).isEqualTo("NGN");
        assertThat(job.salaryPeriod()).isEqualTo(SalaryPeriod.MONTH);
        assertThat(job.normalizedCompany()).isEqualTo("kuda technologies");
    }

    @Test
    void theTargetsCompanyWinsOverTheCompanyTheSourceCalledItself() {
        NormalizerInput input = NormalizerInput.builder("Engineer").companyName("ACME INC").build();

        NormalizedJob job = normalizer.normalize(input, "Acme Corporation");

        assertThat(job.companyName()).isEqualTo("Acme Corporation");
        assertThat(job.normalizedCompany()).isEqualTo("acme");
    }

    @Test
    void theSameJobSpelledDifferentlyBySourcesHasOneFingerprint() {
        NormalizedJob ats = normalizer.normalize(NormalizerInput.builder("Senior Software Engineer")
                .companyName("Acme, Inc.").locationText("Remote - US").build(), null);
        NormalizedJob aggregator = normalizer.normalize(NormalizerInput.builder("Sr. Software Engineer (m/f/d)")
                .companyName("ACME").locationText("United States (Remote)").build(), null);

        assertThat(aggregator.fingerprint()).isEqualTo(ats.fingerprint());
    }

    @ParameterizedTest(name = "[{index}] {0} vs {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Senior Engineer   | Lagos, Nigeria  | Junior Engineer   | Lagos, Nigeria
            Senior Engineer   | Lagos, Nigeria  | Senior Engineer   | Abuja, Nigeria
            Senior Engineer   | Remote - US     | Senior Engineer   | Remote - UK
            Senior Engineer   | Austin, TX      | Senior Engineer   | Austin, Australia
            Engineer (Platform) | Lagos         | Engineer (Payments) | Lagos
            """)
    void differentJobsDoNotCollide(String titleA, String locationA, String titleB, String locationB) {
        String a = normalizer.normalize(NormalizerInput.builder(titleA).companyName("Acme").locationText(locationA)
                .build(), null).fingerprint();
        String b = normalizer.normalize(NormalizerInput.builder(titleB).companyName("Acme").locationText(locationB)
                .build(), null).fingerprint();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void aPostingWithoutATitleOrACompanyIsRejected() {
        assertThatThrownBy(() -> normalizer.normalize(NormalizerInput.builder("   ").companyName("Acme").build(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> normalizer.normalize(NormalizerInput.builder("Engineer").build(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("company");
        assertThatThrownBy(() -> normalizer.normalize(NormalizerInput.builder("(m/f/d)").companyName("Acme").build(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            https://example.com/apply?id=7          | https://example.com/apply?id=7
            http://example.com/jobs/7               | http://example.com/jobs/7
            javascript:alert(1)                     | -
            /careers/apply/7                        | -
            mailto:jobs@example.com                 | -
            https://exa mple.com/x                  | -
            ftp://example.com/job                   | -
            '   '                                   | -
            """)
    void onlyAbsoluteWebApplyLinksAreKept(String raw, String expected) {
        assertThat(JobNormalizer.applyUrl(raw)).isEqualTo(expected);
    }

    @Test
    void overlongFieldsAreTruncatedToTheirColumns() {
        NormalizedJob job = normalizer.normalize(NormalizerInput.builder("Engineer ".repeat(200))
                .companyName("Acme").locationText("Lagos ".repeat(200)).build(), null);

        assertThat(job.title()).hasSizeLessThanOrEqualTo(500);
        assertThat(job.locationRaw()).hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void aSalaryNoOneCouldReadLeavesTheRestOfThePostingIntact() {
        NormalizedJob job = normalizer.normalize(NormalizerInput.builder("Engineer").companyName("Acme")
                .locationText("Accra, Ghana").salaryText("Competitive + equity").build(), null);

        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryCurrency()).isNull();
        assertThat(job.city()).isEqualTo("Accra");
        assertThat(job.workMode()).isEqualTo(WorkMode.ONSITE);
    }

    @Test
    void anExpiryDatePassesThroughAndHtmlInTheTitleIsStripped() {
        Instant expires = Instant.parse("2026-12-31T00:00:00Z");

        NormalizedJob job = normalizer.normalize(NormalizerInput.builder("<b>Data</b> Engineer")
                .companyName("Acme").expiresAt(expires).build(), null);

        assertThat(job.title()).isEqualTo("Data Engineer");
        assertThat(job.expiresAt()).isEqualTo(expires);
    }
}
