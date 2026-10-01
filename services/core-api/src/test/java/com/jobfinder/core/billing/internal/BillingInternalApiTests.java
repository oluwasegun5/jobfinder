package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jobfinder.core.AiServiceStubs;

/**
 * ai-service reports calls whose results it could not hand over (service token only): each is recorded once, as
 * FAILED, and a repeat changes nothing.
 */
class BillingInternalApiTests extends BillingTestSupport {

    private static String entry(UUID callId, UUID userId, String cost) {
        return """
                {"callId":"%s","userId":%s,"feature":"embed_resume","provider":"voyage","model":"voyage-4",
                 "inputTokens":120,"costUsd":"%s","latencyMs":40,"pricingVersion":"2026-10-01"}"""
                .formatted(callId, userId == null ? "null" : "\"" + userId + "\"", cost);
    }

    private ResultActions putUsage(String token, String body) throws Exception {
        var request = put("/internal/v1/billing/usage").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("X-Service-Token", token);
        }
        return mvc.perform(request);
    }

    @Test
    void usageOfCallsWhoseResultsWereLostIsRecordedOnceAsFailed() throws Exception {
        UUID user = newUser();
        UUID callId = UUID.randomUUID();
        String body = "{\"usage\":[" + entry(callId, user, "0.003") + "]}";

        putUsage(AiServiceStubs.TOKEN, body).andExpect(status().isOk()).andExpect(jsonPath("$.recorded").value(1))
                .andExpect(jsonPath("$.duplicates").value(0));
        putUsage(AiServiceStubs.TOKEN, body).andExpect(status().isOk()).andExpect(jsonPath("$.recorded").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));

        Map<String, Object> call = jdbc.queryForMap("select * from ai_calls where request_key = ?",
                "ai-service:" + callId);
        assertThat(call).containsEntry("status", "FAILED").containsEntry("cost_micro_usd", 3_000L)
                .containsEntry("user_id", user);
        assertThat(balance(user)).isEqualByComparingTo("-3");
        assertThat(ledgerLines(user)).isOne();
    }

    @Test
    void noTokenAWrongTokenAndAUsersTokenAreRejected() throws Exception {
        String body = "{\"usage\":[" + entry(UUID.randomUUID(), null, "0.001") + "]}";
        putUsage(null, body).andExpect(status().isUnauthorized());
        putUsage("wrong", body).andExpect(status().isUnauthorized());
        Session session = login(registerVerifiedUser(), PASSWORD, newIp());
        mvc.perform(put("/internal/v1/billing/usage").header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidEntriesAreABadRequestAndRecordNothing() throws Exception {
        UUID callId = UUID.randomUUID();
        putUsage(AiServiceStubs.TOKEN, "{\"usage\":[]}").andExpect(status().isBadRequest());
        putUsage(AiServiceStubs.TOKEN, "{\"usage\":[" + entry(callId, null, "-1") + "]}").andExpect(status().isBadRequest());
        putUsage(AiServiceStubs.TOKEN, "{\"usage\":[{\"feature\":\"x\"}]}").andExpect(status().isBadRequest());

        assertThat(count("select count(*) from ai_calls where request_key = ?", "ai-service:" + callId)).isZero();
    }

    @Test
    void theEndpointIsNotInThePublicOpenApiDocument() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("/internal/");
    }
}
