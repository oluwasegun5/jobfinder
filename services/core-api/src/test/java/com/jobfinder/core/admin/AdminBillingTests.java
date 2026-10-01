package com.jobfinder.core.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jobfinder.core.identity.AuthTestSupport;

/**
 * The AI cost dashboard API: ADMIN only (401 without a token, 403 for a signed-in user), and what it reports. The
 * test data sits in March 2020, far from anything the other tests record, so the figures are exact.
 */
class AdminBillingTests extends AuthTestSupport {

    private static final String RANGE = "?from=2020-03-01&to=2020-03-03";

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        jdbc.update("delete from ai_calls where created_at < '2021-01-01'");
    }

    private void call(String day, String feature, String model, long inputTokens, long outputTokens, long costMicro,
            String status) {
        jdbc.update("insert into ai_calls (id, request_key, feature, provider, model, input_tokens, output_tokens, "
                + "cost_micro_usd, latency_ms, status, created_at) values (?, ?, ?, 'test', ?, ?, ?, ?, 1, ?, "
                + "(?::date + time '13:30') at time zone 'UTC')", UUID.randomUUID(), "t:" + UUID.randomUUID(),
                feature, model, inputTokens, outputTokens, costMicro, status, day);
    }

    private void seed() {
        call("2020-03-01", "parse_resume", "haiku", 1000, 500, 12_000, "SUCCEEDED");
        call("2020-03-01", "embed_job", "voyage", 400, 0, 24, "SUCCEEDED");
        call("2020-03-02", "parse_resume", "haiku", 2000, 800, 20_000, "SUCCEEDED");
        call("2020-03-02", "parse_resume", "haiku", 1000, 300, 6_000, "FAILED");
        call("2020-03-03", "embed_resume", "voyage", 600, 0, 36, "SUCCEEDED");
        // Outside the range: the day before, and the day after.
        call("2020-02-29", "parse_resume", "haiku", 9, 9, 999_999, "SUCCEEDED");
        call("2020-03-04", "parse_resume", "haiku", 9, 9, 999_999, "SUCCEEDED");
    }

    private String token(boolean admin) throws Exception {
        String email = registerVerifiedUser();
        if (admin) {
            jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        }
        return login(email, PASSWORD, newIp()).accessToken();
    }

    private ResultActions costs(String query, String bearer) throws Exception {
        MockHttpServletRequestBuilder request = get("/admin/billing/costs" + query);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    @Test
    void noTokenIsUnauthorizedAndASignedInUserIsForbidden() throws Exception {
        costs(RANGE, null).andExpect(status().isUnauthorized());
        costs(RANGE, token(false)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void anAdminSeesTotalsAndEveryGroupingForTheRange() throws Exception {
        seed();

        costs(RANGE, token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2020-03-01"))
                .andExpect(jsonPath("$.to").value("2020-03-03"))
                .andExpect(jsonPath("$.totals.calls").value(5))
                .andExpect(jsonPath("$.totals.failedCalls").value(1))
                .andExpect(jsonPath("$.totals.inputTokens").value(5000))
                .andExpect(jsonPath("$.totals.outputTokens").value(1600))
                .andExpect(jsonPath("$.totals.costUsd").value(0.03806))
                // By feature, most expensive first.
                .andExpect(jsonPath("$.byFeature.length()").value(3))
                .andExpect(jsonPath("$.byFeature[0].feature").value("parse_resume"))
                .andExpect(jsonPath("$.byFeature[0].calls").value(3))
                .andExpect(jsonPath("$.byFeature[0].failedCalls").value(1))
                .andExpect(jsonPath("$.byFeature[0].costUsd").value(0.038))
                .andExpect(jsonPath("$.byFeature[1].feature").value("embed_resume"))
                .andExpect(jsonPath("$.byFeature[2].feature").value("embed_job"))
                // By day, oldest first.
                .andExpect(jsonPath("$.byDay.length()").value(3))
                .andExpect(jsonPath("$.byDay[0].day").value("2020-03-01"))
                .andExpect(jsonPath("$.byDay[0].calls").value(2))
                .andExpect(jsonPath("$.byDay[0].costUsd").value(0.012024))
                .andExpect(jsonPath("$.byDay[1].day").value("2020-03-02"))
                .andExpect(jsonPath("$.byDay[1].costUsd").value(0.026))
                .andExpect(jsonPath("$.byDay[2].day").value("2020-03-03"))
                // By model, most expensive first.
                .andExpect(jsonPath("$.byModel.length()").value(2))
                .andExpect(jsonPath("$.byModel[0].model").value("haiku"))
                .andExpect(jsonPath("$.byModel[0].costUsd").value(0.038))
                .andExpect(jsonPath("$.byModel[1].model").value("voyage"))
                .andExpect(jsonPath("$.byModel[1].inputTokens").value(1000))
                // Cost per feature per day.
                .andExpect(jsonPath("$.byDayFeature.length()").value(4))
                .andExpect(jsonPath("$.byDayFeature[0].day").value("2020-03-01"))
                .andExpect(jsonPath("$.byDayFeature[0].feature").value("parse_resume"))
                .andExpect(jsonPath("$.byDayFeature[1].feature").value("embed_job"))
                .andExpect(jsonPath("$.byDayFeature[2].day").value("2020-03-02"));
    }

    @Test
    void aSingleDayRangeIsInclusiveOnBothEnds() throws Exception {
        seed();

        costs("?from=2020-03-02&to=2020-03-02", token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.calls").value(2)).andExpect(jsonPath("$.byDay.length()").value(1));
    }

    @Test
    void aRangeWithNoCallsIsAllZeroes() throws Exception {
        costs("?from=2019-01-01&to=2019-01-07", token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.calls").value(0)).andExpect(jsonPath("$.totals.costUsd").value(0))
                .andExpect(jsonPath("$.byFeature").isEmpty()).andExpect(jsonPath("$.byDay").isEmpty());
    }

    @Test
    void withoutARangeItReportsTheLastSevenDays() throws Exception {
        costs("", token(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$.from").value(
                        java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(6).toString()));
    }

    @Test
    void anInvalidRangeIsABadRequest() throws Exception {
        String admin = token(true);
        costs("?from=2020-03-05&to=2020-03-01", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_range"));
        costs("?from=2019-01-01&to=2020-03-01", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_range"));
        costs("?from=yesterday", admin).andExpect(status().isBadRequest());
    }
}
