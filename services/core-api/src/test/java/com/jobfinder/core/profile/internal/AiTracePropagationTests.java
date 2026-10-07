package com.jobfinder.core.profile.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jobfinder.core.AiServiceStubs;
import com.jobfinder.core.TestcontainersConfiguration;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;

/** The trace context rides on the call to ai-service, so its spans join the request's trace (ADR 0039). */
@SpringBootTest
@AutoConfigureTracing
@Import(TestcontainersConfiguration.class)
class AiTracePropagationTests {

    @Autowired
    private AiServiceResumeParser parser;

    @Autowired
    private WireMockServer aiService;

    @Autowired
    private ObservationRegistry observations;

    @Autowired
    private Tracer tracer;

    @Test
    void theCallToAiServiceCarriesTheCurrentTraceparent() {
        String[] traceId = new String[1];
        Observation.createNotStarted("test.request", observations).observe(() -> {
            traceId[0] = tracer.currentSpan().context().traceId();
            parser.parse(UUID.randomUUID(), "not a real cv".getBytes());
        });
        aiService.verify(postRequestedFor(urlPathEqualTo(AiServiceStubs.PARSE_PATH))
                .withHeader("traceparent", matching("00-" + traceId[0] + "-[0-9a-f]{16}-[0-9a-f]{2}")));
    }
}
