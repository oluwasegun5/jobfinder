package com.jobfinder.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jobfinder.core.identity.AuthTestSupport;
import com.jobfinder.core.ingestion.FakeJobSourceAdapter;
import com.jobfinder.core.ingestion.IngestionTestConfig;

/**
 * The source dashboard API (ADR 0024): ADMIN only (401 without a token, 403 for a signed-in user), what the list says,
 * switching a source on and off, starting a run without waiting for it (202, then 409 while it runs) and the run
 * history. The scheduler never ticks on its own here, so nothing reaches a real job board.
 */
@Import(IngestionTestConfig.class)
@TestPropertySource(properties = {
        "app.ingestion.scheduler.poll-interval-ms=3600000",
        "app.ingestion.scheduler.initial-delay-ms=3600000" })
class AdminSourceDashboardTests extends AuthTestSupport {

    private static final String FAKE = "FAKE";

    @Autowired
    @Qualifier("fakeSource")
    private FakeJobSourceAdapter fake;

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        String source = "(select id from sources where code = 'FAKE')";
        jdbc.update("delete from jobs where id in (select job_id from job_sources where source_id = " + source + ")");
        jdbc.update("delete from raw_job_postings where source_id = " + source);
        jdbc.update("delete from source_alerts where source_id = " + source);
        jdbc.update("delete from ingestion_runs where source_id = " + source);
        jdbc.update("delete from source_targets where source_id = " + source);
        jdbc.update("update sources set enabled = true, config = '{}'::jsonb, last_run_at = null, health = 'UNKNOWN' "
                + "where code = 'FAKE'");
        fake.reset();
    }

    private String token(boolean admin) throws Exception {
        String email = registerVerifiedUser();
        if (admin) {
            jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        }
        return login(email, PASSWORD, newIp()).accessToken();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void addTarget(String identifier) {
        jdbc.update("insert into source_targets (id, source_id, identifier, created_at, updated_at) values "
                + "(?, (select id from sources where code = 'FAKE'), ?, now(), now())", UUID.randomUUID(), identifier);
    }

    // ---- authorization

    @Test
    void everyEndpointRequiresAuthenticationAndTheAdminRole() throws Exception {
        List<Supplier<MockHttpServletRequestBuilder>> requests = List.of(
                () -> get("/admin/ingestion/sources"),
                () -> json(put("/admin/ingestion/sources/FAKE/enabled"), "{\"enabled\":false}"),
                () -> post("/admin/ingestion/sources/FAKE/runs"),
                () -> get("/admin/ingestion/runs"));
        String userToken = token(false);

        for (Supplier<MockHttpServletRequestBuilder> request : requests) {
            call(request.get(), null).andExpect(status().isUnauthorized());
            call(request.get(), userToken).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
        }

        assertThat(count("select count(*) from sources where code = 'FAKE' and enabled")).as("nothing changed")
                .isEqualTo(1);
        assertThat(count("select count(*) from ingestion_runs where source_id = "
                + "(select id from sources where code = 'FAKE')")).as("nothing started").isZero();
    }

    // ---- list and switch

    @Test
    void theListShowsEachSourceWithItsStateAndAnAdminCanSwitchOneOffAndOn() throws Exception {
        String admin = token(true);
        addTarget("acme");

        call(get("/admin/ingestion/sources"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].kind").value("ATS"))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].enabled").value(true))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].schedule").value("SCHEDULED"))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].health").value("UNKNOWN"))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].enabledTargets").value(1))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].alerts").isArray())
                .andExpect(jsonPath("$.items[?(@.code=='ADZUNA')].schedule").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.items[?(@.code=='ADZUNA')].unavailableReason").isNotEmpty());

        call(json(put("/admin/ingestion/sources/FAKE/enabled"), "{\"enabled\":false}"), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("FAKE"))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.schedule").value("DISABLED"));
        assertThat(count("select count(*) from sources where code = 'FAKE' and enabled")).isZero();

        call(json(put("/admin/ingestion/sources/FAKE/enabled"), "{\"enabled\":true}"), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.schedule").value("SCHEDULED"));
        assertThat(count("select count(*) from sources where code = 'FAKE' and enabled")).isEqualTo(1);
    }

    @Test
    void switchingNeedsABooleanAndAKnownSource() throws Exception {
        String admin = token(true);

        call(json(put("/admin/ingestion/sources/FAKE/enabled"), "{}"), admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        call(json(put("/admin/ingestion/sources/NOPE/enabled"), "{\"enabled\":false}"), admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("unknown_source"));
    }

    // ---- trigger a run

    @Test
    void startingARunAnswers202AtOnceAndASecondWhileItRunsAnswers409() throws Exception {
        String admin = token(true);
        addTarget("acme");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fake.blockUntilReleased("acme", entered, release);

        call(post("/admin/ingestion/sources/FAKE/runs"), admin).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.source").value("FAKE")).andExpect(jsonPath("$.status").value("STARTED"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        call(get("/admin/ingestion/sources"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].running").value(true));
        call(post("/admin/ingestion/sources/FAKE/runs"), admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("run_in_progress"));

        release.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> count("select count(*) from ingestion_runs "
                + "where source_id = (select id from sources where code = 'FAKE') and status = 'SUCCEEDED'") == 1);

        call(get("/admin/ingestion/sources"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].health").value("HEALTHY"))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].lastRun.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].lastRun.fetched").value(1))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].lastRun.targets").value(1))
                .andExpect(jsonPath("$.items[?(@.code=='FAKE')].running").value(false));
    }

    @Test
    void aRunOfAnUnknownOrKeylessSourceIsRefused() throws Exception {
        String admin = token(true);

        call(post("/admin/ingestion/sources/NOPE/runs"), admin).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("unknown_source"));
        call(post("/admin/ingestion/sources/ADZUNA/runs"), admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("source_unavailable"));
    }

    // ---- history

    @Test
    void theRunHistoryIsPagedAndCanBeFilteredBySource() throws Exception {
        String admin = token(true);
        addTarget("acme");
        addTarget("gone");
        fake.postings("acme", 3);
        fake.failPermanently("gone");
        for (int i = 0; i < 3; i++) {
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> startRunAccepted(admin));
            int expected = i + 1;
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> count("select count(*) from ingestion_runs "
                    + "where source_id = (select id from sources where code = 'FAKE') and status <> 'RUNNING'")
                    >= expected);
        }

        call(get("/admin/ingestion/runs?source=FAKE&size=2"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items[0].source").value("FAKE"))
                .andExpect(jsonPath("$.items[0].status").value("PARTIAL"))
                .andExpect(jsonPath("$.items[0].fetched").value(3)).andExpect(jsonPath("$.items[0].errors").value(1))
                .andExpect(jsonPath("$.items[0].targets").value(2))
                .andExpect(jsonPath("$.items[0].errorSummary").value(org.hamcrest.Matchers.containsString("gone")))
                .andExpect(jsonPath("$.items[0].finishedAt").isNotEmpty());
        call(get("/admin/ingestion/runs?source=FAKE&size=2&page=1"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        call(get("/admin/ingestion/runs"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    void badPagingOrAnUnknownSourceFilterIsRejected() throws Exception {
        String admin = token(true);

        call(get("/admin/ingestion/runs?page=-1"), admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_page"));
        call(get("/admin/ingestion/runs?size=0"), admin).andExpect(status().isBadRequest());
        call(get("/admin/ingestion/runs?size=101"), admin).andExpect(status().isBadRequest());
        call(get("/admin/ingestion/runs?page=abc"), admin).andExpect(status().isBadRequest());
        call(get("/admin/ingestion/runs?source=NOPE"), admin).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("unknown_source"));
    }

    private boolean startRunAccepted(String admin) throws Exception {
        return call(post("/admin/ingestion/sources/FAKE/runs"), admin).andReturn().getResponse().getStatus() == 202;
    }
}
