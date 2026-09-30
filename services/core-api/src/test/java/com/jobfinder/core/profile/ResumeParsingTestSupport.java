package com.jobfinder.core.profile;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.time.Duration;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jobfinder.core.AiServiceStubs;

/**
 * Plumbing for the CV parsing tests: stubs for ai-service that are scoped to one user (so uploads
 * from other tests, parsed in the background, never interfere) and helpers to read parse state.
 */
public abstract class ResumeParsingTestSupport extends ResumeTestSupport {

    @Autowired
    protected WireMockServer aiService;

    protected void stubParse(UUID userId, ResponseDefinitionBuilder response) {
        aiService.stubFor(AiServiceStubs.parseRequest()
                .withQueryParam("user_id", equalTo(userId.toString()))
                .willReturn(response));
    }

    protected void stubParseOk(UUID userId) {
        stubParse(userId, okJson(AiServiceStubs.parseOkBody()));
    }

    /** An RFC 7807 problem response as ai-service sends it. */
    protected static ResponseDefinitionBuilder problem(int status, String code, boolean retryable) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"title\":\"t\",\"status\":%d,\"detail\":\"d\",\"code\":\"%s\",\"retryable\":%s}"
                        .formatted(status, code, retryable));
    }

    protected int parseRequests(UUID userId) {
        return aiService.countRequestsMatching(postRequestedFor(urlPathEqualTo(AiServiceStubs.PARSE_PATH))
                .withQueryParam("user_id", equalTo(userId.toString())).build()).getCount();
    }

    protected String parseStatus(UUID resumeId) {
        return jdbc.queryForObject("select parse_status from resumes where id = ?", String.class, resumeId);
    }

    protected String parseError(UUID resumeId) {
        return jdbc.queryForObject("select parse_error from resumes where id = ?", String.class, resumeId);
    }

    protected void awaitStatus(UUID resumeId, String expected) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> parseStatus(resumeId), expected::equals);
    }

    protected String structured(UUID resumeId, int versionNumber) {
        return jdbc.queryForObject("select structured::text from resume_versions "
                + "where resume_id = ? and version_number = ?", String.class, resumeId, versionNumber);
    }

    protected int versionCount(UUID resumeId) {
        return count("select count(*) from resume_versions where resume_id = ?", resumeId);
    }
}
