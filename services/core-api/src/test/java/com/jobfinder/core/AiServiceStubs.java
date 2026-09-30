package com.jobfinder.core;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;

/**
 * Stubs for the internal ai-service. The canned success body is the contract file that ai-service's
 * own tests pin (tests/test_core_api_contract.py), so both sides agree on the response shape.
 */
public final class AiServiceStubs {

    public static final String TOKEN = "test-ai-service-token-0123456789abcdef";
    public static final String PARSE_PATH = "/v1/parse-resume";

    private AiServiceStubs() {
    }

    /** What ai-service returns for a successfully parsed CV. */
    public static String parseOkBody() {
        try (InputStream in = AiServiceStubs.class.getResourceAsStream("/ai-service/parse-resume-ok.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MappingBuilder parseRequest() {
        return post(urlPathEqualTo(PARSE_PATH));
    }

    /** Low priority, so any stub a test adds wins; keeps unrelated uploads from failing in the background. */
    public static void installDefault(WireMockServer server) {
        server.stubFor(parseRequest().atPriority(10).willReturn(okJson(parseOkBody())));
    }
}
