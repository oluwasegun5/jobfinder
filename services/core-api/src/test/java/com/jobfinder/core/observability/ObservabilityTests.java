package com.jobfinder.core.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;

import com.jobfinder.core.AiServiceStubs;
import com.jobfinder.core.identity.AuthTestSupport;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;

/** One request is traceable: trace id on the response, the same id in the logging context, metrics scrapeable. */
@AutoConfigureMetrics
@AutoConfigureTracing
class ObservabilityTests extends AuthTestSupport {

    private static final String TOKEN_HEADER = "X-Service-Token";

    @Autowired
    private ObservationRegistry observations;

    @Autowired
    private Tracer tracer;

    @Test
    void everyResponseCarriesATraceId() throws Exception {
        String traceId = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn().getResponse()
                .getHeader("X-Trace-Id");
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void anErrorFromTheSecurityChainCarriesOneToo() throws Exception {
        String traceId = mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized()).andReturn().getResponse()
                .getHeader("X-Trace-Id");
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void anIncomingTraceContinuesInsteadOfStartingANewOne() throws Exception {
        String incoming = "4bf92f3577b34da6a3ce929d0e0e4736";
        String echoed = mvc.perform(get("/actuator/health").header("traceparent",
                "00-" + incoming + "-00f067aa0ba902b7-01")).andReturn().getResponse().getHeader("X-Trace-Id");
        assertThat(echoed).isEqualTo(incoming);
    }

    @Test
    void theLoggingContextCarriesTheCurrentTraceAndSpanIds() {
        Observation.createNotStarted("test.work", observations).observe(() -> {
            String traceId = tracer.currentSpan().context().traceId();
            assertThat(MDC.get("traceId")).isEqualTo(traceId);
            assertThat(MDC.get("spanId")).isEqualTo(tracer.currentSpan().context().spanId());
        });
    }

    @Test
    void prometheusNeedsTheServiceToken() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header(TOKEN_HEADER, "wrong-token-0123456789abcdef0123456789"))
                .andExpect(status().isUnauthorized());
        // A signed-in user, even an admin, is not the scraper.
        String user = registerVerifiedUser();
        Session session = login(user, PASSWORD, newIp());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusServesTheMetricsToTheScraper() throws Exception {
        registerVerifiedUser();
        mvc.perform(get("/actuator/health"));
        String body = mvc.perform(get("/actuator/prometheus").header(TOKEN_HEADER, AiServiceStubs.TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("jvm_memory_used_bytes").contains("http_server_requests_seconds_bucket")
                .contains("jobs_active").contains("funnel_users{stage=\"registered\"")
                .contains("funnel_users{stage=\"matched\"").contains("funnel_users{stage=\"applied\"");
        // A real count, not NaN (the gauge must not be collected from under Micrometer).
        assertThat(body).containsPattern("(?m)^funnel_users\\{stage=\"registered\"[^}]*\\} \\d+(\\.\\d+)?(E\\d+)?$")
                .containsPattern("(?m)^jobs_active(\\{[^}]*\\})? \\d+(\\.\\d+)?(E\\d+)?$");
    }
}
