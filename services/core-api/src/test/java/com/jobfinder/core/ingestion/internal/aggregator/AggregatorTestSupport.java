package com.jobfinder.core.ingestion.internal.aggregator;

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
 * WireMock stands in for the five aggregators: no test reaches a real third-party API and none needs a real
 * key (the keys here are synthetic markers, which the failure tests then look for in messages). The fixtures
 * under {@code src/test/resources/aggregators} are synthetic postings shaped like what each source returns.
 */
abstract class AggregatorTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String APP_ID = "test-app-id-marker";
    static final String APP_KEY = "test-app-key-marker";
    static final String RAPID_KEY = "test-rapid-key-marker";

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

    /** Keys set, two results per page, generous budgets and no pacing: tests that need less override one source. */
    static AggregatorProperties properties() {
        return properties(Duration.ofSeconds(5), 33_554_432L);
    }

    static AggregatorProperties properties(Duration readTimeout, long maxResponseBytes) {
        return new AggregatorProperties(Duration.ofSeconds(2), readTimeout, maxResponseBytes, adzuna(APP_ID, APP_KEY, 2, 2, 100),
                jsearch(RAPID_KEY, 2, 100), new AggregatorProperties.Remotive(baseUrl(), 100, Duration.ZERO),
                new AggregatorProperties.Arbeitnow(baseUrl(), 3, 100, Duration.ZERO),
                new AggregatorProperties.RemoteOk(baseUrl(), 100, Duration.ZERO));
    }

    static AggregatorProperties.Adzuna adzuna(String id, String key, int perPage, int pages, int perDay) {
        return new AggregatorProperties.Adzuna(baseUrl(), id, key, perPage, pages, 30, perDay, Duration.ZERO);
    }

    static AggregatorProperties.JSearch jsearch(String key, int pages, int perDay) {
        return new AggregatorProperties.JSearch(baseUrl(), "jsearch.test", key, pages, "month", perDay, Duration.ZERO);
    }

    static AggregatorProperties with(AggregatorProperties p, AggregatorProperties.Adzuna adzuna,
            AggregatorProperties.JSearch jsearch) {
        return new AggregatorProperties(p.connectTimeout(), p.readTimeout(), p.maxResponseBytes(), adzuna, jsearch,
                p.remotive(), p.arbeitnow(), p.remoteok());
    }

    static AggregatorHttp http(AggregatorProperties properties) {
        return new AggregatorHttp(properties, JSON);
    }

    static FetchTarget target(String identifier) {
        return new FetchTarget(UUID.randomUUID(), identifier, null);
    }

    static String fixture(String name) {
        try (InputStream in = AggregatorTestSupport.class.getResourceAsStream("/aggregators/" + name)) {
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
