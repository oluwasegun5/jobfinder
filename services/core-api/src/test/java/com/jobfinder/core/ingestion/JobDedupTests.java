package com.jobfinder.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Runs produce normalized jobs, and the same job listed twice becomes one job with two listings. */
class JobDedupTests extends JobPipelineTestSupport {

    @Test
    void aRunProducesANormalizedJobItsCompanyAndItsListing() {
        UUID target = addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("p-1", "Sr. Backend Engineer (m/f/d)", "Acme, Inc.", "Austin, TX",
                "employmentType", "Full-time", "salaryText", "$140k - $170k a year",
                "description", "<p>Build APIs</p>", "applyUrl", "https://acme.example/jobs/1"));

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.updated()).isZero();
        Map<String, Object> job = jdbc.queryForMap("select * from jobs where id = ?", jobIdOf(FAKE, "p-1"));
        assertThat(job).containsEntry("title", "Sr. Backend Engineer (m/f/d)")
                .containsEntry("normalized_title", "senior backend engineer")
                .containsEntry("city", "Austin").containsEntry("country", "US")
                .containsEntry("work_mode", "ONSITE").containsEntry("employment_type", "FULL_TIME")
                .containsEntry("seniority", "SENIOR").containsEntry("salary_currency", "USD")
                .containsEntry("salary_period", "YEAR").containsEntry("status", "ACTIVE")
                .containsEntry("description_text", "Build APIs")
                .containsEntry("apply_url", "https://acme.example/jobs/1");
        assertThat(job.get("fingerprint")).asString().hasSize(64);
        assertThat(jdbc.queryForObject("select name from companies where id = ?", String.class, job.get("company_id")))
                .isEqualTo("Acme, Inc.");
        Map<String, Object> link = jdbc.queryForMap("select * from job_sources where external_id = 'p-1'");
        assertThat(link).containsEntry("target_id", target).containsEntry("missed_runs", 0)
                .containsEntry("url", "https://acme.example/jobs/1");
    }

    @Test
    void theSameJobFromTwoSourcesIsOneJobWithTwoListings() {
        addTarget(FAKE, "acme");
        addTarget(FAKE_AGG, "search");
        fake.customPostings("acme", posting("ats-7", "Senior Software Engineer", "Acme, Inc.", "Remote - US"));
        fakeAgg.customPostings("search",
                posting("agg-99", "Sr. Software Engineer (m/f/d)", "ACME", "United States (Remote)"));

        IngestionRunSummary first = ingestion.runNow(FAKE).orElseThrow();
        IngestionRunSummary second = ingestion.runNow(FAKE_AGG).orElseThrow();

        assertThat(first.created()).isEqualTo(1);
        assertThat(second.created()).as("merged, not created").isZero();
        assertThat(second.updated()).isEqualTo(1);
        assertThat(jobCount()).isEqualTo(1);
        UUID job = jobIdOf(FAKE, "ats-7");
        assertThat(jobIdOf(FAKE_AGG, "agg-99")).isEqualTo(job);
        assertThat(linkCount(job)).isEqualTo(2);
        assertThat(companyCount("acme")).isEqualTo(1);
    }

    @Test
    void aMergeFillsWhatTheJobLacksButNeverRewritesWhatTheOwnerSaid() {
        addTarget(FAKE, "acme");
        addTarget(FAKE_AGG, "search");
        fake.customPostings("acme", posting("ats-1", "Senior Engineer", "Acme", "Lagos, Nigeria",
                "description", "<p>Our own words</p>"));
        fakeAgg.customPostings("search", posting("agg-1", "Sr Engineer", "Acme", "Lagos",
                "description", "<p>Aggregator words</p>", "salaryMin", "500000", "salaryMax", "700000",
                "salaryCurrency", "NGN", "salaryPeriod", "month", "employmentType", "Permanent"));
        ingestion.runNow(FAKE).orElseThrow();

        ingestion.runNow(FAKE_AGG).orElseThrow();

        Map<String, Object> job = jdbc.queryForMap("select * from jobs where id = ?", jobIdOf(FAKE, "ats-1"));
        assertThat(job).containsEntry("title", "Senior Engineer")
                .containsEntry("description_text", "Our own words")
                .containsEntry("salary_currency", "NGN").containsEntry("salary_period", "MONTH")
                .containsEntry("employment_type", "FULL_TIME");
        assertThat((java.math.BigDecimal) job.get("salary_max")).isEqualByComparingTo("700000");
    }

    @Test
    void theOwnersRefreshOverwritesTheJobButAnotherListingsRefreshDoesNot() {
        addTarget(FAKE, "acme");
        addTarget(FAKE_AGG, "search");
        fake.customPostings("acme", posting("ats-1", "Senior Engineer", "Acme", "Lagos", "description", "v1"));
        fakeAgg.customPostings("search", posting("agg-1", "Senior Engineer", "Acme", "Lagos"));
        ingestion.runNow(FAKE).orElseThrow();
        ingestion.runNow(FAKE_AGG).orElseThrow();

        fakeAgg.customPostings("search",
                posting("agg-1", "Senior Engineer", "Acme", "Lagos", "description", "aggregator rewrite"));
        ingestion.runNow(FAKE_AGG).orElseThrow();
        assertThat(jdbc.queryForObject("select description_text from jobs where id = ?", String.class,
                jobIdOf(FAKE, "ats-1"))).as("filled only when empty, and v1 is not empty").isEqualTo("v1");

        fake.customPostings("acme", posting("ats-1", "Senior Engineer", "Acme", "Lagos", "description", "v2"));
        ingestion.runNow(FAKE).orElseThrow();
        assertThat(jdbc.queryForObject("select description_text from jobs where id = ?", String.class,
                jobIdOf(FAKE, "ats-1"))).isEqualTo("v2");
    }

    @Test
    void runningTheSamePostingAgainChangesNothingButTheCounters() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("p-1", "Engineer", "Acme", "Lagos"));
        ingestion.runNow(FAKE).orElseThrow();

        IngestionRunSummary again = ingestion.runNow(FAKE).orElseThrow();

        assertThat(again.created()).isZero();
        assertThat(again.updated()).isEqualTo(1);
        assertThat(jobCount()).isEqualTo(1);
        assertThat(linkCount(jobIdOf(FAKE, "p-1"))).isEqualTo(1);
    }

    @Test
    void aRetitledPostingUpdatesItsJobInPlaceWhenNothingElseListsIt() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("p-1", "Engineer", "Acme", "Lagos"));
        ingestion.runNow(FAKE).orElseThrow();
        UUID job = jobIdOf(FAKE, "p-1");
        String before = jdbc.queryForObject("select fingerprint from jobs where id = ?", String.class, job);

        fake.customPostings("acme", posting("p-1", "Senior Engineer", "Acme", "Lagos"));
        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.created()).isZero();
        assertThat(jobCount()).isEqualTo(1);
        assertThat(jobIdOf(FAKE, "p-1")).isEqualTo(job);
        assertThat(jdbc.queryForMap("select title, seniority, fingerprint from jobs where id = ?", job))
                .containsEntry("title", "Senior Engineer").containsEntry("seniority", "SENIOR")
                .doesNotContainEntry("fingerprint", before);
    }

    @Test
    void aRetitledListingThatNowMatchesAnotherJobMovesThereAndLeavesItsOldJobExpired() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("p-1", "Engineer", "Acme", "Lagos"),
                posting("p-2", "Senior Engineer", "Acme", "Lagos"));
        ingestion.runNow(FAKE).orElseThrow();
        UUID oldJob = jobIdOf(FAKE, "p-1");

        fake.customPostings("acme", posting("p-1", "Senior Engineer", "Acme", "Lagos"),
                posting("p-2", "Senior Engineer", "Acme", "Lagos"));
        ingestion.runNow(FAKE).orElseThrow();

        assertThat(jobIdOf(FAKE, "p-1")).isEqualTo(jobIdOf(FAKE, "p-2")).isNotEqualTo(oldJob);
        assertThat(linkCount(jobIdOf(FAKE, "p-2"))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, oldJob))
                .isEqualTo("EXPIRED");
    }

    @Test
    void twoListingsOfTheSameRoleInOneSourceShareOneJob() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("req-1", "Support Agent", "Acme", "Lagos"),
                posting("req-2", "Support Agent", "Acme", "Lagos"));

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.updated()).isEqualTo(1);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void sameTitleInDifferentCitiesOrAtDifferentCompaniesAreDifferentJobs() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("a", "Engineer", "Acme", "Lagos, Nigeria"),
                posting("b", "Engineer", "Acme", "Abuja, Nigeria"),
                posting("c", "Engineer", "Globex", "Lagos, Nigeria"));

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.created()).isEqualTo(3);
        assertThat(jobCount()).isEqualTo(3);
        assertThat(companyCount("acme")).isEqualTo(1);
        assertThat(companyCount("globex")).isEqualTo(1);
    }

    @Test
    void aTargetThatNamesItsCompanyKeepsAllItsJobsUnderThatCompany() {
        UUID company = addCompany("Acme Corporation", "acme");
        addTarget(FAKE, "acme-board", company);
        fake.customPostings("acme-board", posting("a", "Engineer", "Acme Inc.", "Lagos"),
                posting("b", "Designer", "ACME Holdings", "Lagos"));

        ingestion.runNow(FAKE).orElseThrow();

        assertThat(jdbc.queryForList("select distinct company_id from jobs where id in "
                + "(select job_id from job_sources where source_id = ?)", UUID.class, sourceId(FAKE)))
                .containsExactly(company);
        assertThat(companyCount("acme")).as("no second Acme was created").isEqualTo(1);
        assertThat(companyCount("acme holdings")).isZero();
    }

    @Test
    void anUnreadablePostingIsRejectedOnItsOwnAndTheRunCarriesOn() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("good", "Engineer", "Acme", "Lagos"),
                FakeJobSourceAdapter.raw("no-title", Map.of("company", "Acme")),
                FakeJobSourceAdapter.raw("no-company", Map.of("title", "Designer")));

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.fetched()).isEqualTo(3);
        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.errors()).isZero();
        assertThat(rawPostingCount(FAKE)).as("rejected postings stay raw, ready for reprocessing").isEqualTo(3);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void descriptionHtmlIsSanitizedBeforeItIsStored() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("p-1", "Engineer", "Acme", "Lagos", "description",
                "<p onclick=\"x()\">Hello</p><script>steal()</script><a href=\"javascript:bad()\">go</a>"));

        ingestion.runNow(FAKE).orElseThrow();

        String html = jdbc.queryForObject("select description_html from jobs where id = ?", String.class,
                jobIdOf(FAKE, "p-1"));
        assertThat(html).contains("Hello").doesNotContain("script").doesNotContain("onclick")
                .doesNotContain("javascript:");
    }

    @Test
    void theRunRecordCountsJobsNotRawPostings() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", posting("a", "Engineer", "Acme", "Lagos"),
                posting("b", "Engineer", "Acme", "Lagos"), posting("c", "Designer", "Acme", "Lagos"));

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        Map<String, Object> run = jdbc.queryForMap("select * from ingestion_runs where id = ?", summary.runId());
        assertThat(run).containsEntry("fetched", 3).containsEntry("created", 2).containsEntry("updated", 1)
                .containsEntry("expired", 0);
        assertThat(jobTitles()).containsExactly("Designer", "Engineer");
    }
}
