package com.jobfinder.core.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The five aggregator adapters through the real pipeline, normalizer and database, with WireMock standing in for
 * the sources and synthetic keys: a stubbed search in, jobs and stored attribution out. The adapters' own mapping
 * is covered by their unit tests; this is about what lands in {@code jobs}, {@code job_sources} and
 * {@code sources}, and about the 45-day expiry rule that aggregators live under (ADR 0019).
 */
class AggregatorIngestionTests extends IngestionTestSupport {

    private static final String[] AGGREGATORS = { "ADZUNA", "JSEARCH", "REMOTIVE", "ARBEITNOW", "REMOTEOK" };

    private static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + WIREMOCK.port();
        String prefix = "app.ingestion.aggregators.";
        registry.add(prefix + "adzuna.base-url", () -> base);
        registry.add(prefix + "adzuna.app-id", () -> "test-app-id");
        registry.add(prefix + "adzuna.app-key", () -> "test-app-key");
        registry.add(prefix + "jsearch.base-url", () -> base);
        registry.add(prefix + "jsearch.api-key", () -> "test-rapid-key");
        registry.add(prefix + "remotive.base-url", () -> base);
        registry.add(prefix + "arbeitnow.base-url", () -> base);
        registry.add(prefix + "remoteok.base-url", () -> base);
        for (String source : new String[] { "adzuna", "jsearch", "remotive", "arbeitnow", "remoteok" }) {
            registry.add(prefix + source + ".min-request-interval", () -> "0ms");
            registry.add(prefix + source + ".max-requests-per-day", () -> "1000");
        }
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @Autowired
    SourceTargetService targets;

    @Autowired
    JobListingService listings;

    @BeforeEach
    void cleanAggregators() {
        WIREMOCK.resetAll();
        for (String code : AGGREGATORS) {
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
        try (InputStream in = AggregatorIngestionTests.class.getResourceAsStream("/aggregators/" + name)) {
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
                select j.id, j.title, j.work_mode, j.employment_type, j.city, j.country, j.salary_min, j.salary_max,
                       j.salary_currency, j.salary_period, j.apply_url, j.posted_at, j.expires_at, j.status,
                       j.description_text, c.name as company, js.url as listing_url
                from jobs j join job_sources js on js.job_id = j.id join companies c on c.id = j.company_id
                where js.source_id = (select id from sources where code = ?) and js.external_id = ?
                """, code, externalId);
    }

    private IngestionRunSummary run(String code, String identifier) {
        targets.addTarget(code, identifier, null);
        return ingestion.runNow(code).orElseThrow();
    }

    @Test
    void adzunaAdsBecomeJobsWithoutItsSalaryEstimates() {
        stub("/v1/api/jobs/gb/search/1", "adzuna/page-1.json");

        IngestionRunSummary summary = run("ADZUNA", "gb:software engineer");

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.fetched()).isEqualTo(2);
        assertThat(summary.created()).isEqualTo(2);
        Map<String, Object> paid = job("ADZUNA", "4400000001");
        assertThat(paid).containsEntry("title", "Senior Software Engineer").containsEntry("company", "Northwind Labs")
                .containsEntry("country", "GB").containsEntry("work_mode", "ONSITE")
                .containsEntry("employment_type", "FULL_TIME").containsEntry("salary_currency", "GBP")
                .containsEntry("salary_period", "YEAR");
        assertThat((BigDecimal) paid.get("salary_min")).isEqualByComparingTo("65000");
        assertThat((String) paid.get("listing_url")).startsWith("https://www.adzuna.example/land/ad/4400000001");
        Map<String, Object> estimated = job("ADZUNA", "4400000002");
        assertThat(estimated).containsEntry("employment_type", "CONTRACT").containsEntry("city", "London");
        assertThat(estimated.get("salary_min")).isNull();
    }

    @Test
    void remotiveJobsBecomeRemoteJobsLinkedToTheirRemotivePage() {
        stub("/api/remote-jobs", "remotive/all.json");

        IngestionRunSummary summary = run("REMOTIVE", "all");

        assertThat(summary.created()).isEqualTo(2);
        Map<String, Object> backend = job("REMOTIVE", "2000001");
        assertThat(backend).containsEntry("work_mode", "REMOTE").containsEntry("employment_type", "FULL_TIME")
                .containsEntry("company", "Litware").containsEntry("salary_currency", "USD");
        assertThat((String) backend.get("listing_url")).startsWith("https://remotive.example/remote-jobs/");
        assertThat(job("REMOTIVE", "2000002")).containsEntry("employment_type", "CONTRACT");
    }

    @Test
    void arbeitnowJobsBecomeJobsAcrossPages() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/job-board-api")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson(fixture("arbeitnow/page-1.json"))));
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/job-board-api")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson(fixture("arbeitnow/page-2.json"))));

        IngestionRunSummary summary = run("ARBEITNOW", "all");

        assertThat(summary.created()).isEqualTo(3);
        assertThat(job("ARBEITNOW", "backend-developer-acme-gmbh-berlin-100001")).containsEntry("city", "Berlin")
                .containsEntry("country", "DE").containsEntry("work_mode", "ONSITE");
        assertThat(job("ARBEITNOW", "devops-engineer-globex-remote-100002")).containsEntry("work_mode", "REMOTE");
        assertThat(job("ARBEITNOW", "werkstudent-initech-munich-100003")).containsEntry("employment_type", "INTERNSHIP");
    }

    @Test
    void remoteOkJobsBecomeRemoteJobsWithTheirSalary() {
        stub("/api", "remoteok/api.json");

        IngestionRunSummary summary = run("REMOTEOK", "all");

        assertThat(summary.fetched()).isEqualTo(2);
        assertThat(summary.created()).isEqualTo(2);
        Map<String, Object> paid = job("REMOTEOK", "3000001");
        assertThat(paid).containsEntry("work_mode", "REMOTE").containsEntry("salary_currency", "USD")
                .containsEntry("salary_period", "YEAR").containsEntry("company", "Hooli");
        assertThat((BigDecimal) paid.get("salary_max")).isEqualByComparingTo("160000");
        assertThat(job("REMOTEOK", "3000002").get("salary_min")).isNull();
    }

    @Test
    void jsearchJobsBecomeJobs() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/search")).withHeader("X-RapidAPI-Key", equalTo("test-rapid-key"))
                .willReturn(okJson(fixture("jsearch/search-page-2.json"))));
        WIREMOCK.stubFor(get(urlPathEqualTo("/search")).withQueryParam("page", equalTo("1"))
                .withHeader("X-RapidAPI-Key", equalTo("test-rapid-key"))
                .willReturn(okJson(fixture("jsearch/search-page-1.json"))));

        IngestionRunSummary summary = run("JSEARCH", "ng:software developer jobs in Nigeria");

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.created()).isEqualTo(2);
        Map<String, Object> backend = job("JSEARCH", "synthetic-job-id-0001==");
        assertThat(backend).containsEntry("title", "Backend Developer").containsEntry("company", "Tailspin Africa")
                .containsEntry("city", "Lagos").containsEntry("country", "NG").containsEntry("salary_period", "MONTH")
                .containsEntry("employment_type", "FULL_TIME");
        assertThat(backend.get("expires_at")).isNotNull();
        assertThat(job("JSEARCH", "synthetic-job-id-0002==")).containsEntry("work_mode", "REMOTE")
                .containsEntry("employment_type", "CONTRACT");
    }

    @Test
    void attributionIsStoredOnTheSourceAndHandedOutWithEachListing() {
        stub("/api", "remoteok/api.json");
        run("REMOTEOK", "all");
        UUID jobId = (UUID) job("REMOTEOK", "3000001").get("id");

        Map<String, Object> source = jdbc.queryForMap(
                "select attribution_name, attribution_text, attribution_url, attribution_notes from sources where code = 'REMOTEOK'");
        assertThat(source).containsEntry("attribution_name", "Remote OK").containsEntry("attribution_url",
                "https://remoteok.com");
        assertThat((String) source.get("attribution_notes")).contains("nofollow");
        for (String code : AGGREGATORS) {
            assertThat(jdbc.queryForObject("select attribution_text from sources where code = ?", String.class, code))
                    .as(code).isNotBlank();
        }
        assertThat(jdbc.queryForObject("select attribution_name from sources where code = 'FAKE'", String.class)).isNull();

        List<JobListing> shown = listings.listingsOf(List.of(jobId)).get(jobId);
        assertThat(shown).hasSize(1);
        assertThat(shown.get(0).sourceCode()).isEqualTo("REMOTEOK");
        assertThat(shown.get(0).sourceKind()).isEqualTo(SourceKind.AGGREGATOR);
        assertThat(shown.get(0).listingUrl()).endsWith("remote-senior-java-engineer-hooli-3000001");
        assertThat(shown.get(0).attribution().text()).isEqualTo("Remote OK");
        assertThat(listings.listingsOf(List.of(UUID.randomUUID()))).isEmpty();
        assertThat(listings.listingsOf(List.of())).isEmpty();
    }

    @Test
    void aJobAnAggregatorNoLongerReturnsStaysActiveUntilFortyFiveDaysUnseen() {
        stub("/api", "remoteok/api.json");
        run("REMOTEOK", "all");
        WIREMOCK.stubFor(get(urlPathEqualTo("/api")).willReturn(okJson("[{\"legal\":\"terms\"}]")));

        // Not returned for several runs: an aggregator's results rotate, so this is not "gone".
        for (int i = 0; i < 3; i++) {
            IngestionRunSummary summary = ingestion.runNow("REMOTEOK").orElseThrow();
            assertThat(summary.expired()).isZero();
        }
        assertThat(job("REMOTEOK", "3000001")).containsEntry("status", "ACTIVE");
        assertThat(jdbc.queryForObject("select max(missed_runs) from job_sources where source_id = "
                + "(select id from sources where code = 'REMOTEOK')", Integer.class)).isZero();

        jdbc.update("update job_sources set last_seen_at = now() - interval '46 days' where source_id = "
                + "(select id from sources where code = 'REMOTEOK')");
        IngestionRunSummary stale = ingestion.runNow("REMOTEOK").orElseThrow();

        assertThat(stale.expired()).isEqualTo(2);
        assertThat(job("REMOTEOK", "3000001")).containsEntry("status", "EXPIRED");
    }

    @Test
    void aRejectedKeyFailsOnlyThatTargetAndLeaksNothing() {
        stub("/v1/api/jobs/gb/search/1", "adzuna/page-1.json");
        WIREMOCK.stubFor(get(urlPathEqualTo("/v1/api/jobs/us/search/1")).willReturn(aResponse().withStatus(401)));
        targets.addTarget("ADZUNA", "us:software engineer", null);
        targets.addTarget("ADZUNA", "gb:software engineer", null);

        IngestionRunSummary summary = ingestion.runNow("ADZUNA").orElseThrow();

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.PARTIAL);
        assertThat(summary.created()).isEqualTo(2);
        assertThat(health("ADZUNA")).isEqualTo("DEGRADED");
        String error = jdbc.queryForObject("select error_summary from ingestion_runs where id = ?", String.class,
                summary.runId());
        assertThat(error).contains("HTTP 401").doesNotContain("http").doesNotContain("test-app-key")
                .doesNotContain("test-app-id").doesNotContain("localhost");
    }

    @Test
    void anAggregatorSearchHasNoCompanyAndAnAtsBoardStillNeedsOne() {
        SourceTargetView search = targets.addTarget("ADZUNA", "ca:software engineer", null);

        assertThat(search.created()).isTrue();
        assertThat(search.companyName()).isNull();
        assertThat(targets.addTarget("ADZUNA", "ca:software engineer", " ").created()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> targets.addTarget("ADZUNA", "ca:other", "Some Co"))
                .isInstanceOf(InvalidSourceTargetException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> targets.addTarget("GREENHOUSE", "someboard", null))
                .isInstanceOf(InvalidSourceTargetException.class);
    }
}
