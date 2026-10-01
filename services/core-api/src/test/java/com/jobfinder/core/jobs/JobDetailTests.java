package com.jobfinder.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The job page: the job in full, and every source's listing with the credit its terms require. */
class JobDetailTests extends JobsTestSupport {

    private UUID aggregatorWithCredit() {
        return jdbc.queryForObject("select id from sources where attribution_name is not null order by code limit 1",
                UUID.class);
    }

    private UUID sourceWithoutCredit() {
        return jdbc.queryForObject("select id from sources where attribution_name is null and kind = 'ATS' "
                + "order by code limit 1", UUID.class);
    }

    private void list(UUID job, UUID source, String externalId, String url, Instant createdAt) {
        jdbc.update("insert into job_sources (id, job_id, source_id, external_id, url, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), job, source, externalId, url, at(createdAt),
                at(createdAt));
    }

    @Test
    void theJobComesWithItsFieldsAndPlainTextDescription() throws Exception {
        UUID job = insert(spec().title("Backend Engineer").company("Acme").city("Lagos").country("NG")
                .workMode("HYBRID").employmentType("FULL_TIME").seniority("SENIOR")
                .salary("50000", "80000", "USD", "YEAR").skills("Java", "SQL")
                .description("Line one\n- item <b>bold</b>\n\n<script>alert(1)</script>")
                .postedAt(Instant.parse("2026-09-20T08:00:00Z")));
        Session me = newSession();

        getAs(me, "/jobs/" + job).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.toString()))
                .andExpect(jsonPath("$.title").value("Backend Engineer"))
                .andExpect(jsonPath("$.company.name").value("Acme"))
                .andExpect(jsonPath("$.location").value("Lagos"))
                .andExpect(jsonPath("$.workMode").value("HYBRID"))
                .andExpect(jsonPath("$.seniority").value("SENIOR"))
                .andExpect(jsonPath("$.salary.currency").value("USD"))
                .andExpect(jsonPath("$.skills[0]").value("Java"))
                .andExpect(jsonPath("$.applyUrl").value("https://jobs.example.test/apply"))
                .andExpect(jsonPath("$.postedAt").value("2026-09-20T08:00:00Z"))
                // The text is returned exactly as stored; it is data, and the page renders it as text.
                .andExpect(jsonPath("$.description").value("Line one\n- item <b>bold</b>\n\n<script>alert(1)</script>"))
                .andExpect(jsonPath("$.descriptionHtml").doesNotExist())
                .andExpect(jsonPath("$.listings.length()").value(0))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))));
    }

    @Test
    void everyListingIsReturnedOldestFirstWithTheAttributionOfItsSource() throws Exception {
        UUID job = insert("Shared job");
        UUID ats = sourceWithoutCredit();
        UUID aggregator = aggregatorWithCredit();
        list(job, aggregator, "agg-1", "https://agg.example.test/job/1", Instant.parse("2026-09-02T00:00:00Z"));
        list(job, ats, "ats-1", "https://boards.example.test/acme/1", Instant.parse("2026-09-01T00:00:00Z"));
        Session me = newSession();

        String aggregatorName = jdbc.queryForObject("select attribution_name from sources where id = ?",
                String.class, aggregator);
        getAs(me, "/jobs/" + job).andExpect(status().isOk())
                .andExpect(jsonPath("$.listings.length()").value(2))
                .andExpect(jsonPath("$.listings[0].sourceKind").value("ATS"))
                .andExpect(jsonPath("$.listings[0].url").value("https://boards.example.test/acme/1"))
                .andExpect(jsonPath("$.listings[0].attribution").doesNotExist())
                .andExpect(jsonPath("$.listings[1].sourceKind").value("AGGREGATOR"))
                .andExpect(jsonPath("$.listings[1].url").value("https://agg.example.test/job/1"))
                .andExpect(jsonPath("$.listings[1].attribution.name").value(aggregatorName))
                .andExpect(jsonPath("$.listings[1].attribution.text").isNotEmpty())
                .andExpect(jsonPath("$.listings[1].attribution.url").isNotEmpty());
    }

    @Test
    void anExpiredJobCanStillBeOpened() throws Exception {
        UUID job = insert(spec().title("Closed").status("EXPIRED"));
        Session me = newSession();

        getAs(me, "/jobs/" + job).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(count("select count(*) from jobs where id = ?", job)).isEqualTo(1);
    }
}
