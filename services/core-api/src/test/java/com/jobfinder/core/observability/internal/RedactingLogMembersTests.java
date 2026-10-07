package com.jobfinder.core.observability.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The JSON log format as shipped: trace id present, PII masked in the message and in the stack trace. */
class RedactingLogMembersTests {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private JsonNode encode(String message, Throwable error) throws Exception {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "core-api")
                .withProperty("logging.structured.json.customizer", RedactingLogMembers.class.getName());
        context.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        Logger logger = context.getLogger("test");
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.WARN, message, error, null);
        context.getMDCAdapter().put("traceId", TRACE_ID);
        context.getMDCAdapter().put("spanId", "00f067aa0ba902b7");
        event.prepareForDeferredProcessing();
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(line.strip()).doesNotContain("\n");
        return JsonMapper.builder().build().readTree(line);
    }

    @Test
    void oneJsonObjectPerLineWithTheTraceId() throws Exception {
        JsonNode json = encode("run finished", null);
        assertThat(json.path("message").asString()).isEqualTo("run finished");
        assertThat(json.toString()).contains(TRACE_ID);
    }

    @Test
    void masksPiiInTheMessageAndTheStackTrace() throws Exception {
        Exception error = new IllegalStateException("duplicate key (email)=(ada@example.test)");
        JsonNode json = encode("login failed for ada@example.test, Bearer abc.def.ghi, password=hunter2hunter2", error);
        String line = json.toString();
        assertThat(line).doesNotContain("ada@example.test").doesNotContain("abc.def.ghi")
                .doesNotContain("hunter2hunter2").contains("[email]");
        assertThat(json.path("error").path("message").asString()).doesNotContain("ada@example.test");
    }
}
