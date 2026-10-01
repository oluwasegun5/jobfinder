package com.jobfinder.core.ingestion.internal.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jobfinder.core.ingestion.FetchTarget;

import tools.jackson.databind.json.JsonMapper;

/**
 * WireMock stands in for the six job boards: no test reaches a real third-party API. The fixtures under
 * {@code src/test/resources/ats} are synthetic postings shaped like what each board returns.
 */
abstract class AtsTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static WireMockServer wiremock;

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wiremock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wiremock.resetAll();
    }

    static String baseUrl() {
        return "http://localhost:" + wiremock.port();
    }

    static AtsProperties properties() {
        return properties(Duration.ofSeconds(5), 33_554_432L, 100, 100);
    }

    static AtsProperties properties(Duration readTimeout, long maxResponseBytes, int leverPageSize,
            int smartRecruitersMaxDetails) {
        String base = baseUrl();
        return new AtsProperties(Duration.ofSeconds(2), readTimeout, maxResponseBytes, base, base, leverPageSize, base,
                base, base, smartRecruitersMaxDetails, Duration.ZERO, base);
    }

    static AtsHttp http(AtsProperties properties) {
        return new AtsHttp(properties, JSON);
    }

    static FetchTarget target(String identifier) {
        return new FetchTarget(UUID.randomUUID(), identifier, null);
    }

    static String fixture(String name) {
        try (InputStream in = AtsTestSupport.class.getResourceAsStream("/ats/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void stubJson(String path, String fixtureName) {
        wiremock.stubFor(get(urlPathEqualTo(path)).willReturn(okJson(fixture(fixtureName))));
    }
}
