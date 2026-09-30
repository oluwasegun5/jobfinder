package com.jobfinder.core.profile.internal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls ai-service {@code POST /v1/parse-resume}. Turns every outcome into either a
 * {@link Parsed} result or a {@link ParseFailure} that says whether a retry could help.
 *
 * <p>The response body holds the person's CV data: it is never logged, and only its shape is
 * checked here (the content was already validated against a strict schema by ai-service).
 */
@Component
class AiServiceResumeParser {

    /** The stored form of a parse: opaque JSON plus what produced it. */
    record Parsed(String structuredJson, String model, String promptVersion) {
    }

    private static final Logger log = LoggerFactory.getLogger(AiServiceResumeParser.class);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STRUCTURED_CHARS = 256 * 1024;
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final RestClient client;
    private final JsonMapper json;

    AiServiceResumeParser(AiServiceProperties properties, JsonMapper json) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Service-Token", properties.token())
                .requestFactory(factory)
                .build();
        this.json = json;
    }

    Parsed parse(UUID userId, byte[] file) {
        try {
            return client.post()
                    .uri(uri -> uri.path("/v1/parse-resume").queryParam("user_id", userId).build())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(file)
                    .exchange((request, response) -> interpret(response.getStatusCode().value(),
                            response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1)));
        } catch (ResourceAccessException e) {
            throw ParseFailure.transientFailure("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            throw new ParseFailure(ParseFailureReason.PARSER_ERROR, false, "ai-service call failed", e);
        }
    }

    private Parsed interpret(int status, byte[] body) throws IOException {
        if (body.length > MAX_RESPONSE_BYTES) {
            throw ParseFailure.permanent(ParseFailureReason.INVALID_PARSER_RESPONSE, "ai-service response too large");
        }
        return status == 200 ? success(body) : failure(status, body);
    }

    private Parsed success(byte[] body) {
        Map<String, Object> response = readObject(body);
        Object structured = response == null ? null : response.get("structured");
        if (!(structured instanceof Map<?, ?> resume)
                || !Integer.valueOf(SUPPORTED_SCHEMA_VERSION).equals(resume.get("schema_version"))
                || !(response.get("prompt_version") instanceof String promptVersion) || promptVersion.isBlank()) {
            throw invalidResponse();
        }
        String model = lastModel(response.get("usage"));
        String structuredJson;
        try {
            structuredJson = json.writeValueAsString(structured);
        } catch (JacksonException e) {
            throw invalidResponse();
        }
        if (structuredJson.length() > MAX_STRUCTURED_CHARS || model == null) {
            throw invalidResponse();
        }
        logUsage(response.get("usage"));
        return new Parsed(structuredJson, model, promptVersion);
    }

    private Parsed failure(int status, byte[] body) {
        Map<String, Object> problem = readObject(body);
        String code = problem != null && problem.get("code") instanceof String c ? c : null;
        boolean retryable = problem != null && Boolean.TRUE.equals(problem.get("retryable"));
        log.warn("ai-service refused a parse (status={}, code={}, retryable={})", status, code, retryable);

        if (status == 401 || status == 403 || "llm_not_configured".equals(code)) {
            // Our own misconfiguration; retrying cannot help and users cannot fix it.
            throw ParseFailure.permanent(ParseFailureReason.PARSER_UNAVAILABLE, "ai-service is misconfigured");
        }
        var reported = ParseFailureReason.fromParserCode(code);
        if (reported.isPresent()) {
            // The file or the model output is the problem; the same request would fail the same way.
            throw ParseFailure.permanent(reported.get(), "ai-service reported " + code);
        }
        if (status == 429 || status >= 500 || retryable) {
            throw ParseFailure.transientFailure("ai-service unavailable (status " + status + ")", null);
        }
        throw ParseFailure.permanent(ParseFailureReason.PARSER_ERROR, "ai-service returned status " + status);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(byte[] body) {
        try {
            return json.readValue(body, Map.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String lastModel(Object usage) {
        if (usage instanceof List<?> calls && !calls.isEmpty()
                && calls.get(calls.size() - 1) instanceof Map<?, ?> last && last.get("model") instanceof String model
                && !model.isBlank()) {
            return model;
        }
        return null;
    }

    /** Usage goes to the ledger in Phase 3 (ADR 0008); until then it is logged, without any CV content. */
    private void logUsage(Object usage) {
        if (usage instanceof List<?> calls) {
            for (Object call : calls) {
                if (call instanceof Map<?, ?> u) {
                    log.info("ai usage user={} feature={} provider={} model={} inputTokens={} outputTokens={} costUsd={} "
                            + "latencyMs={} promptVersion={}", u.get("user_id"), u.get("feature"), u.get("provider"),
                            u.get("model"), u.get("input_tokens"), u.get("output_tokens"), u.get("cost_usd"),
                            u.get("latency_ms"), u.get("prompt_version"));
                }
            }
        }
    }

    private static ParseFailure invalidResponse() {
        return ParseFailure.permanent(ParseFailureReason.INVALID_PARSER_RESPONSE, "unexpected ai-service response");
    }
}
