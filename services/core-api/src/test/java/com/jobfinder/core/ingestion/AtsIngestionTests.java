package com.jobfinder.core.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The six ATS adapters through the real pipeline, normalizer and database, with WireMock standing in for
 * the boards: a stubbed board in, jobs out. The adapters' own mapping is covered by their unit tests; this
 * is about what lands in {@code jobs}.
 */
class AtsIngestionTests extends IngestionTestSupport {

    private static final String[] ATS = { "GREENHOUSE", "LEVER", "ASHBY", "WORKABLE", "SMARTRECRUITERS", "RECRUITEE" };

    private static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void boards(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + WIREMOCK.port();
        registry.add("app.ingestion.ats.greenhouse-base-url", () -> base);
        registry.add("app.ingestion.ats.lever-base-url", () -> base);
        registry.add("app.ingestion.ats.ashby-base-url", () -> base);
        registry.add("app.ingestion.ats.workable-base-url", () -> base);
        registry.add("app.ingestion.ats.smart-recruiters-base-url", () -> base);
        registry.add("app.ingestion.ats.smart-recruiters-detail-delay", () -> "0ms");
        registry.add("app.ingestion.ats.recruitee-base-url", () -> base);
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @Autowired
    SourceTargetService targets;

    @BeforeEach
    void cleanAts() {
        WIREMOCK.resetAll();
        for (String code : ATS) {
            jdbc.update("delete from jobs where id in (select job_id from job_sources "
                    + "where source_id = (select id from sources where code = ?))", code);
            jdbc.update("delete from raw_job_postings where source_id = (select id from sources where code = ?)", code);
            jdbc.update("delete from ingestion_runs where source_id = (select id from sources where code = ?)", code);
            jdbc.update("delete from source_targets where source_id = (select id from sources where code = ?)", code);
            jdbc.update("update sources set enabled = true, config = '{}'::jsonb, last_run_at = null, "
                    + "health = 'UNKNOWN' where code = ?", code);
        }
        jdbc.update("delete from companies where id not in (select company_id from jobs) "
                + "and id not in (select company_id from source_targets where company_id is not null)");
    }

    private static String fixture(String name) {
        try (InputStream in = AtsIngestionTests.class.getResourceAsStream("/ats/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void stub(String path, String fixtureName) {
        WIREMOCK.stubFor(get(urlPathEqualTo(path)).willReturn(okJson(fixture(fixtureName))));
    }

    private Map<String, Object> job(String code, String externalId) {
        return jdbc.queryForMap("""
                select j.title, j.work_mode, j.employment_type, j.country, j.salary_min, j.salary_max,
                       j.salary_currency, j.salary_period, j.apply_url, j.posted_at, j.expires_at, j.status,
                       j.description_text, c.name as company
                from jobs j join job_sources js on js.job_id = j.id join companies c on c.id = j.company_id
                where js.source_id = (select id from sources where code = ?) and js.external_id = ?
                """, code, externalId);
    }

    private IngestionRunSummary run(String code, String token, String company) {
        targets.addTarget(code, token, company);
        return ingestion.runNow(code).orElseThrow();
    }

    @Test
    void greenhouseBoardBecomesJobs() {
        stub("/v1/boards/examplecorp/jobs", "greenhouse/board.json");

        IngestionRunSummary summary = run("GREENHOUSE", "examplecorp", "Example Corp");

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.fetched()).isEqualTo(2);
        assertThat(summary.created()).isEqualTo(2);
        Map<String, Object> remote = job("GREENHOUSE", "4001001");
        assertThat(remote).containsEntry("title", "Senior Backend Engineer").containsEntry("company", "Example Corp")
                .containsEntry("work_mode", "REMOTE").containsEntry("salary_currency", "USD")
                .containsEntry("salary_period", "YEAR");
        assertThat((BigDecimal) remote.get("salary_min")).isEqualByComparingTo("90000");
        assertThat((BigDecimal) remote.get("salary_max")).isEqualByComparingTo("120000");
        assertThat((String) remote.get("description_text")).contains("Build services in Java").contains("- Own APIs");
        Map<String, Object> office = job("GREENHOUSE", "4001002");
        assertThat(office).containsEntry("work_mode", "ONSITE").containsEntry("country", "NG");
        assertThat(office.get("salary_min")).isNull();
    }

    @Test
    void leverPostingsBecomeJobs() {
        stub("/v0/postings/examplelabs", "lever/page-full-1.json");

        IngestionRunSummary summary = run("LEVER", "examplelabs", "Example Labs");

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.created()).isEqualTo(2);
        Map<String, Object> hybrid = job("LEVER", "7c1e0000-0000-4000-8000-000000000001");
        assertThat(hybrid).containsEntry("work_mode", "HYBRID").containsEntry("employment_type", "FULL_TIME")
                .containsEntry("country", "DE").containsEntry("salary_currency", "EUR")
                .containsEntry("salary_period", "YEAR");
        assertThat((String) hybrid.get("description_text")).contains("Join the data platform team.")
                .contains("Design pipelines");
        Map<String, Object> remote = job("LEVER", "7c1e0000-0000-4000-8000-000000000002");
        assertThat(remote).containsEntry("work_mode", "REMOTE").containsEntry("employment_type", "CONTRACT");
    }

    @Test
    void ashbyJobsBecomeJobsAndUnlistedOnesAreLeftOut() {
        stub("/posting-api/job-board/exampleai", "ashby/board.json");

        IngestionRunSummary summary = run("ASHBY", "exampleai", "Example AI");

        assertThat(summary.created()).isEqualTo(2);
        Map<String, Object> remote = job("ASHBY", "a5b00000-0000-4000-8000-000000000001");
        assertThat(remote).containsEntry("work_mode", "REMOTE").containsEntry("employment_type", "FULL_TIME")
                .containsEntry("salary_currency", "EUR").containsEntry("salary_period", "YEAR");
        Map<String, Object> hybrid = job("ASHBY", "a5b00000-0000-4000-8000-000000000003");
        assertThat(hybrid).containsEntry("work_mode", "HYBRID").containsEntry("employment_type", "PART_TIME");
        assertThat(jdbc.queryForObject("select count(*) from job_sources where external_id = ?", Integer.class,
                "a5b00000-0000-4000-8000-000000000002")).isZero();
    }

    @Test
    void workableJobsBecomeJobsUnderTheAccountName() {
        stub("/api/v1/widget/accounts/exampleworks", "workable/account.json");

        IngestionRunSummary summary = run("WORKABLE", "exampleworks", "Example Works");

        assertThat(summary.created()).isEqualTo(2);
        assertThat(job("WORKABLE", "AAA0000001")).containsEntry("work_mode", "REMOTE")
                .containsEntry("employment_type", "FULL_TIME").containsEntry("country", "GH")
                .containsEntry("company", "Example Works");
        assertThat(job("WORKABLE", "AAA0000002")).containsEntry("work_mode", "ONSITE")
                .containsEntry("employment_type", "PART_TIME").containsEntry("country", "KE");
    }

    @Test
    void smartRecruitersJobsBecomeJobsEvenWithoutADetail() {
        WIREMOCK.stubFor(get(urlEqualTo("/v1/companies/examplehq/postings?limit=100&offset=0"))
                .willReturn(okJson(fixture("smartrecruiters/list-1.json"))));
        stub("/v1/companies/examplehq/postings/7440001", "smartrecruiters/detail-7440001.json");
        stub("/v1/companies/examplehq/postings/7440002", "smartrecruiters/detail-7440002.json");
        WIREMOCK.stubFor(get(urlEqualTo("/v1/companies/examplehq/postings/7440003"))
                .willReturn(aResponse().withStatus(404)));

        IngestionRunSummary summary = run("SMARTRECRUITERS", "examplehq", "Example HQ");

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.created()).isEqualTo(3);
        Map<String, Object> platform = job("SMARTRECRUITERS", "7440001");
        assertThat(platform).containsEntry("work_mode", "HYBRID").containsEntry("employment_type", "FULL_TIME");
        assertThat((String) platform.get("description_text")).contains("Run the platform.").contains("Kubernetes");
        assertThat(job("SMARTRECRUITERS", "7440002")).containsEntry("work_mode", "REMOTE")
                .containsEntry("employment_type", "CONTRACT");
        assertThat(job("SMARTRECRUITERS", "7440003")).containsEntry("work_mode", "ONSITE").containsEntry("country", "ZA");
    }

    @Test
    void recruiteeOffersBecomeJobsWithSalaryAndClosingDate() {
        stub("/api/offers/", "recruitee/offers.json");

        IngestionRunSummary summary = run("RECRUITEE", "examplebv", "Example BV");

        assertThat(summary.created()).isEqualTo(2);
        Map<String, Object> backend = job("RECRUITEE", "5550001");
        assertThat(backend).containsEntry("work_mode", "HYBRID").containsEntry("employment_type", "FULL_TIME")
                .containsEntry("salary_currency", "EUR").containsEntry("salary_period", "MONTH");
        assertThat((BigDecimal) backend.get("salary_min")).isEqualByComparingTo("4000");
        Map<String, Object> support = job("RECRUITEE", "5550002");
        assertThat(support).containsEntry("work_mode", "REMOTE").containsEntry("employment_type", "PART_TIME");
        assertThat(support.get("expires_at")).isNotNull();
    }

    @Test
    void anUnknownBoardFailsOnlyItsTargetAndLeaksNothing() {
        stub("/v1/boards/examplecorp/jobs", "greenhouse/board.json");
        WIREMOCK.stubFor(get(urlPathEqualTo("/v1/boards/retired/jobs")).willReturn(aResponse().withStatus(404)));
        targets.addTarget("GREENHOUSE", "retired", "Retired Inc");
        targets.addTarget("GREENHOUSE", "examplecorp", "Example Corp");

        IngestionRunSummary summary = ingestion.runNow("GREENHOUSE").orElseThrow();

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.PARTIAL);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.created()).isEqualTo(2);
        assertThat(health("GREENHOUSE")).isEqualTo("DEGRADED");
        String error = jdbc.queryForObject("select error_summary from ingestion_runs where id = ?", String.class,
                summary.runId());
        assertThat(error).contains("retired").doesNotContain("http").doesNotContain("localhost");
    }

    @Test
    void aJobTheBoardStopsListingExpiresAfterTwoMissedRuns() {
        stub("/v1/boards/examplecorp/jobs", "greenhouse/board.json");
        run("GREENHOUSE", "examplecorp", "Example Corp");
        WIREMOCK.stubFor(get(urlPathEqualTo("/v1/boards/examplecorp/jobs")).willReturn(okJson("""
                {"jobs":[{"id":4001001,"title":"Senior Backend Engineer","company_name":"Example Corp",
                "location":{"name":"Remote - Nigeria"},"absolute_url":"https://boards.example.test/1",
                "first_published":"2026-09-01T09:30:00-04:00","content":"&lt;p&gt;Build.&lt;/p&gt;"}],
                "meta":{"total":1}}""")));

        IngestionRunSummary second = ingestion.runNow("GREENHOUSE").orElseThrow();
        assertThat(second.expired()).isZero();
        IngestionRunSummary third = ingestion.runNow("GREENHOUSE").orElseThrow();

        assertThat(third.expired()).isEqualTo(1);
        assertThat(job("GREENHOUSE", "4001002")).containsEntry("status", "EXPIRED");
        assertThat(job("GREENHOUSE", "4001001")).containsEntry("status", "ACTIVE");
    }
}
