package com.jobfinder.core.profile;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jobfinder.core.AiServiceStubs;

/** Uploading a CV queues parsing through RabbitMQ; the worker calls ai-service (WireMock) and stores the result. */
class ResumeParsingTests extends ResumeParsingTestSupport {

    @Test
    void uploadingACvResultsInAParsedResumeVersion() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);

        upload(session, "cv.pdf", "application/pdf", pdf()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.parseStatus").value("PENDING"));
        UUID id = UUID.fromString(jdbc.queryForObject("select id::text from resumes where user_id = ?", String.class,
                userId));
        awaitStatus(id, "PARSED");

        assertThat(versionCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select structured ->> 'schema_version' from resume_versions "
                + "where resume_id = ? and version_number = 1 and source = 'UPLOAD'", String.class, id)).isEqualTo("1");
        assertThat(jdbc.queryForObject("select structured -> 'contact' ->> 'full_name' from resume_versions "
                + "where resume_id = ?", String.class, id)).isEqualTo("Jordan Reyes");
        assertThat(count("select jsonb_array_length(structured -> 'experience') from resume_versions "
                + "where resume_id = ?", id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select model || ' ' || prompt_version from resume_versions "
                + "where resume_id = ?", String.class, id)).isEqualTo("fake-fast parse_resume/v1");
        assertThat(parseError(id)).isNull();

        mvc.perform(get("/resumes").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(jsonPath("$[0].parseStatus").value("PARSED"))
                .andExpect(jsonPath("$[0].parseError").doesNotExist());
    }

    @Test
    void theParserIsCalledWithTheServiceTokenTheUserAndTheOriginalFile() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);

        UUID id = uploadPdf(session);
        awaitStatus(id, "PARSED");

        aiService.verify(1, postRequestedFor(urlPathEqualTo(AiServiceStubs.PARSE_PATH))
                .withQueryParam("user_id", equalTo(userId.toString()))
                .withHeader("X-Service-Token", equalTo(AiServiceStubs.TOKEN))
                .withHeader("Content-Type", equalTo("application/octet-stream"))
                .withRequestBody(binaryEqualTo(pdf())));
    }

    @ParameterizedTest
    @CsvSource({
            "422, no_extractable_text, no_extractable_text",
            "422, unreadable_file, unreadable_file",
            "415, unsupported_file_type, unsupported_file_type",
            "413, file_too_large, file_too_large",
            "400, empty_file, empty_file",
            "422, llm_refused, llm_refused",
            "502, llm_output_invalid, llm_output_invalid",
            // Unknown codes are never stored verbatim.
            "422, '<script>alert(1)</script>', parser_error",
            "409, something_new, parser_error" })
    void aFileTheParserCannotUseSetsFailedWithAReasonAndIsNotRetried(int httpStatus, String code, String reason)
            throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParse(userId, problem(httpStatus, code, false));

        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        assertThat(parseError(id)).isEqualTo(reason);
        assertThat(parseRequests(userId)).isEqualTo(1);
        assertThat(structured(id, 1)).isNull();
        mvc.perform(get("/resumes").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(jsonPath("$[0].parseStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].parseError").value(reason));
    }

    @ParameterizedTest
    @CsvSource({ "503, llm_not_configured", "401, ''", "403, ''" })
    void aMisconfiguredParserFailsAtOnceWithoutRetries(int httpStatus, String code) throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParse(userId, problem(httpStatus, code, false));

        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        assertThat(parseError(id)).isEqualTo("parser_unavailable");
        assertThat(parseRequests(userId)).isEqualTo(1);
    }

    @Test
    void anUnavailableParserIsRetriedAndThenTheParseFailsWithAReason() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParse(userId, problem(502, "llm_unavailable", true));

        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        assertThat(parseError(id)).isEqualTo("parser_unavailable");
        assertThat(parseRequests(userId)).isEqualTo(3);
    }

    @Test
    void aDroppedConnectionIsRetriedToo() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParse(userId, aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        assertThat(parseError(id)).isEqualTo("parser_unavailable");
        assertThat(parseRequests(userId)).isEqualTo(3);
    }

    @Test
    void aTransientFailureThatClearsUpEndsInASuccessfulParse() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        String scenario = "flaky-" + userId;
        String userParam = userId.toString();
        aiService.stubFor(AiServiceStubs.parseRequest().withQueryParam("user_id", equalTo(userParam))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(problem(503, "llm_unavailable", true)).willSetStateTo("second"));
        aiService.stubFor(AiServiceStubs.parseRequest().withQueryParam("user_id", equalTo(userParam))
                .inScenario(scenario).whenScenarioStateIs("second")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("third"));
        aiService.stubFor(AiServiceStubs.parseRequest().withQueryParam("user_id", equalTo(userParam))
                .inScenario(scenario).whenScenarioStateIs("third")
                .willReturn(okJson(AiServiceStubs.parseOkBody())));

        UUID id = uploadPdf(session);
        awaitStatus(id, "PARSED");

        assertThat(parseRequests(userId)).isEqualTo(3);
        assertThat(parseError(id)).isNull();
        assertThat(versionCount(id)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            not json at all
            {"structured": {"schema_version": 2}, "prompt_version": "parse_resume/v1", "usage": [{"model": "m"}]}
            {"structured": {"schema_version": 1}, "usage": [{"model": "m"}]}
            {"structured": {"schema_version": 1}, "prompt_version": "parse_resume/v1", "usage": []}
            {"structured": "a string", "prompt_version": "parse_resume/v1", "usage": [{"model": "m"}]}
            [1, 2, 3]
            """)
    void aSuccessResponseThatIsNotWhatWasPromisedFailsTheParse(String body) throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParse(userId, okJson(body));

        UUID id = uploadPdf(session);
        awaitStatus(id, "FAILED");

        assertThat(parseError(id)).isEqualTo("invalid_parser_response");
        assertThat(structured(id, 1)).isNull();
        assertThat(parseRequests(userId)).isEqualTo(1);
    }

    @Test
    void parsingOneResumeNeverTouchesAnother() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParse(userId, problem(422, "no_extractable_text", false));
        UUID first = uploadPdf(session);
        awaitStatus(first, "FAILED");

        stubParseOk(userId);
        // Newer stubs win in WireMock, so the second upload is parsed successfully.
        UUID second = uploadPdf(session);
        awaitStatus(second, "PARSED");

        assertThat(parseStatus(first)).isEqualTo("FAILED");
        assertThat(parseError(second)).isNull();
    }

    @Test
    void makingAnotherResumePrimaryDoesNotRevertAParseResult() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);
        UUID first = uploadPdf(session);
        UUID second = uploadPdf(session);
        awaitStatus(first, "PARSED");
        awaitStatus(second, "PARSED");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/resumes/" + second + "/primary").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parseStatus").value("PARSED"));

        assertThat(parseStatus(first)).isEqualTo("PARSED");
        assertThat(parseStatus(second)).isEqualTo("PARSED");
    }
}
