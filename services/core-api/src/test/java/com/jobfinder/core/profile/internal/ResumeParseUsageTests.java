package com.jobfinder.core.profile.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.jobfinder.core.AiServiceStubs;
import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;
import com.jobfinder.core.profile.ResumeParsingTestSupport;

/**
 * Resume parsing and the usage ledger (ADR 0025): every billed call ai-service reports is recorded, on success and on
 * failure, and a user who has used up the daily cap gets no parse: the resume is FAILED with
 * {@code ai_daily_cap_reached} and can be parsed again after the reset.
 */
@TestPropertySource(properties = { "app.billing.daily-cap-credits=10", "app.billing.micro-usd-per-credit=1000" })
class ResumeParseUsageTests extends ResumeParsingTestSupport {

    @Autowired
    private AiUsageLedger ledger;

    private String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    /** Spends {@code costUsd} of the user's allowance, as an earlier AI call would have. */
    private void spend(UUID userId, String costUsd) {
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), userId, "parse_resume", "test", "test-model", 1, 1,
                new BigDecimal(costUsd), 1, null, null, AiCallStatus.SUCCEEDED));
    }

    private Map<String, Object> call(UUID callId) {
        return jdbc.queryForMap("select * from ai_calls where request_key = ?", "ai-service:" + callId);
    }

    private BigDecimal balance(UUID userId) {
        return jdbc.queryForObject("select coalesce(sum(delta), 0) from credit_ledger where user_id = ? "
                + "and reason = 'AI_USAGE'", BigDecimal.class, userId);
    }

    @Test
    void aParsedCvIsRecordedAsUsageAndDebitedToItsOwner() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        UUID callId = UUID.randomUUID();
        stubParse(userId, okJson(AiServiceStubs.parseOkBody(callId, "0.003")));

        UUID id = uploadPdf(session);
        awaitStatus(id, "PARSED");

        assertThat(call(callId)).containsEntry("user_id", userId).containsEntry("feature", "parse_resume")
                .containsEntry("provider", "fake").containsEntry("model", "fake-fast")
                .containsEntry("prompt_version", "parse_resume/v1").containsEntry("pricing_version", "fake")
                .containsEntry("cost_micro_usd", 3_000L).containsEntry("status", "SUCCEEDED");
        assertThat(balance(userId)).isEqualByComparingTo("-3");
    }

    @Test
    void everyBilledAttemptOfAFailedParseIsRecordedToo() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // ai-service tried twice (the validation retry) and failed: both attempts were billed.
        stubParse(userId, aResponse().withStatus(502).withHeader("Content-Type", "application/problem+json")
                .withBody("""
                        {"title":"t","status":502,"detail":"d","code":"llm_output_invalid","retryable":true,
                         "usage":[%s,%s]}""".formatted(entry(first, "0.004"), entry(second, "0.005"))));

        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        assertThat(parseError(id)).isEqualTo("llm_output_invalid");
        assertThat(call(first)).containsEntry("status", "FAILED").containsEntry("cost_micro_usd", 4_000L);
        assertThat(call(second)).containsEntry("status", "FAILED").containsEntry("cost_micro_usd", 5_000L);
        assertThat(balance(userId)).isEqualByComparingTo("-9");
    }

    @Test
    void aFailureThatReportsNoUsageRecordsNothing() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParse(userId, problem(422, "no_extractable_text", false));

        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        assertThat(count("select count(*) from ai_calls where user_id = ?", userId)).isZero();
    }

    @Test
    void aMalformedUsageEntryDoesNotFailAnOtherwiseGoodParse() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        UUID good = UUID.randomUUID();
        String body = AiServiceStubs.parseOkBody(good, "0.002").replace("\"usage\": [", """
                "usage": [{"feature": "parse_resume", "cost_usd": "not-a-number"},""");
        stubParse(userId, okJson(body));

        UUID id = uploadPdf(session);
        awaitStatus(id, "PARSED");

        assertThat(call(good)).containsEntry("cost_micro_usd", 2_000L);
    }

    @Test
    void aUserWhoUsedUpTheDailyCapGetsNoParseAndAClearReason() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);
        spend(userId, "0.02");

        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        assertThat(parseError(id)).isEqualTo("ai_daily_cap_reached");
        assertThat(parseRequests(userId)).isZero();
        mvc.perform(get("/resumes").header("Authorization", bearer(session)))
                .andExpect(jsonPath("$[0].parseStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].parseError").value("ai_daily_cap_reached"));
    }

    @Test
    void parsingAgainIsRefusedWhileTheCapIsUsedUpAndWorksOnceItHasReset() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);
        spend(userId, "0.02");
        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        // Still the same day: a clear 429 with the reset time, and nothing is queued or called.
        mvc.perform(post("/resumes/" + id + "/reparse").header("Authorization", bearer(session)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("ai_daily_cap_reached"))
                .andExpect(jsonPath("$.resetsAt").isNotEmpty());
        assertThat(parseStatus(id)).isEqualTo("FAILED");
        assertThat(parseRequests(userId)).isZero();

        // The next UTC day, the day's spending no longer counts. (The ledger is append-only except for deletion, so
        // the test removes the day's lines; the reset arithmetic itself is covered in DailyCapTests.)
        jdbc.update("delete from credit_ledger where user_id = ?", userId);

        mvc.perform(post("/resumes/" + id + "/reparse").header("Authorization", bearer(session)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.parseStatus").value("PENDING"))
                .andExpect(jsonPath("$.parseError").doesNotExist());
        awaitStatus(id, "PARSED");
        assertThat(parseRequests(userId)).isEqualTo(1);
        assertThat(versionCount(id)).isEqualTo(1);
    }

    @Test
    void onlyAFailureThatIsNotTheFilesFaultCanBeParsedAgain() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);
        UUID parsed = uploadPdf(session);
        awaitStatus(parsed, "PARSED");
        stubParse(userId, problem(422, "no_extractable_text", false));
        UUID bad = uploadPdf(session);
        awaitStatus(bad, "FAILED");

        for (UUID id : List.of(parsed, bad)) {
            mvc.perform(post("/resumes/" + id + "/reparse").header("Authorization", bearer(session)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("reparse_not_allowed"));
        }
    }

    @Test
    void reparseIsScopedToTheOwnerAndNeedsASignIn() throws Exception {
        Session owner = newSession();
        UUID ownerId = userIdOf(owner.accessToken());
        spend(ownerId, "0.02");
        UUID id = uploadPdf(owner);
        awaitStatus(id, "FAILED");
        Session other = newSession();

        mvc.perform(post("/resumes/" + id + "/reparse").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("resume_not_found"));
        mvc.perform(post("/resumes/" + id + "/reparse")).andExpect(status().isUnauthorized());
    }

    private static String entry(UUID callId, String costUsd) {
        return """
                {"call_id":"%s","user_id":"00000000-0000-4000-8000-000000000001","feature":"parse_resume",
                 "provider":"fake","model":"fake-fast","input_tokens":10,"output_tokens":5,"cost_usd":"%s",
                 "latency_ms":1,"prompt_version":"parse_resume/v1","pricing_version":"fake"}"""
                .formatted(callId, costUsd);
    }
}
