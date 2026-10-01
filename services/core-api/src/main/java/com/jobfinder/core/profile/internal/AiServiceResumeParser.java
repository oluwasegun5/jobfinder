package com.jobfinder.core.profile.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls ai-service {@code POST /v1/parse-resume}. Turns every outcome into either a
 * {@link Parsed} result or a {@link ParseFailure} that says whether a retry could help.
 *
 * <p>Every billed call the response reports goes to the usage ledger (docs/adr/0025-ai-usage-ledger.md), on success
 * and on failure alike: ai-service lists the calls it made in the error body too, so a parse that fails after a
 * billed attempt still costs the user's allowance.
 *
 * <p>The response body holds the person's CV data: it is never logged, and only its shape is
 * checked here (the content was already validated against a strict schema by ai-service).
 */
@Component
class AiServiceResumeParser {

    /**
     * The stored form of a parse: opaque JSON plus what produced it. {@code warningsJson} is a JSON array of
     * {@code {path, code}} grounding warnings (possibly empty), already reduced to that shape.
     */
    record Parsed(String structuredJson, String warningsJson, String model, String promptVersion) {
    }

    private static final Logger log = LoggerFactory.getLogger(AiServiceResumeParser.class);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STRUCTURED_CHARS = 256 * 1024;
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_WARNINGS = 200;
    private static final int MAX_WARNING_FIELD_CHARS = 100;

    private final RestClient client;
    private final JsonMapper json;
    private final AiUsageLedger ledger;

    AiServiceResumeParser(AiServiceProperties properties, JsonMapper json, AiUsageLedger ledger) {
        this.ledger = ledger;
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
                    .exchange((request, response) -> interpret(userId, response.getStatusCode().value(),
                            response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1)));
        } catch (ResourceAccessException e) {
            throw ParseFailure.transientFailure("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            throw new ParseFailure(ParseFailureReason.PARSER_ERROR, false, "ai-service call failed", e);
        }
    }

    private Parsed interpret(UUID userId, int status, byte[] body) throws IOException {
        if (body.length > MAX_RESPONSE_BYTES) {
            throw ParseFailure.permanent(ParseFailureReason.INVALID_PARSER_RESPONSE, "ai-service response too large");
        }
        return status == 200 ? success(userId, body) : failure(userId, status, body);
    }

    private Parsed success(UUID userId, byte[] body) {
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
        String warningsJson;
        try {
            warningsJson = json.writeValueAsString(warnings(response.get("warnings")));
        } catch (JacksonException e) {
            throw invalidResponse();
        }
        recordUsage(userId, response.get("usage"), AiCallStatus.SUCCEEDED);
        return new Parsed(structuredJson, warningsJson, model, promptVersion);
    }

    /**
     * Keeps only well-formed {@code {path, code}} string pairs. Warnings are advisory and shown to the user, so a
     * malformed one is dropped rather than failing an otherwise good parse.
     */
    private static List<Map<String, String>> warnings(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item instanceof Map<?, ?> m && shortString(m.get("path")) && shortString(m.get("code")))
                .limit(MAX_WARNINGS)
                .map(item -> Map.of("path", (String) ((Map<?, ?>) item).get("path"), "code",
                        (String) ((Map<?, ?>) item).get("code")))
                .toList();
    }

    private static boolean shortString(Object value) {
        return value instanceof String s && !s.isBlank() && s.length() <= MAX_WARNING_FIELD_CHARS;
    }

    private Parsed failure(UUID userId, int status, byte[] body) {
        Map<String, Object> problem = readObject(body);
        if (problem != null) {
            // Calls billed before the failure (a validation retry, a refusal) are still recorded.
            recordUsage(userId, problem.get("usage"), AiCallStatus.FAILED);
        }
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

    /**
     * Records each reported call, once (ai-service's {@code call_id} is the idempotency key), against the user the parse
     * was requested for (not whatever the response says). A malformed entry is
     * skipped with a warning. If the ledger itself is unavailable the parse is not failed (the work was paid for
     * and the result is good): the figures are logged at ERROR so the spend can be reconciled, and nothing
     * personal is in the line.
     */
    private void recordUsage(UUID userId, Object usage, AiCallStatus status) {
        if (!(usage instanceof List<?> calls)) {
            return;
        }
        for (Object call : calls) {
            Optional<AiUsage> parsed = call instanceof Map<?, ?> u ? toUsage(userId, u, status) : Optional.empty();
            if (parsed.isEmpty()) {
                log.warn("Skipping a malformed ai-service usage entry");
                continue;
            }
            try {
                ledger.record(parsed.get());
            } catch (RuntimeException e) {
                AiUsage u = parsed.get();
                log.error("UNRECORDED ai usage call={} user={} feature={} model={} inputTokens={} outputTokens={} "
                        + "costUsd={}", u.requestKey(), u.userId(), u.feature(), u.model(), u.inputTokens(),
                        u.outputTokens(), u.costUsd(), e);
            }
        }
    }

    private static Optional<AiUsage> toUsage(UUID userId, Map<?, ?> u, AiCallStatus status) {
        try {
            UUID callId = u.get("call_id") instanceof String id ? UUID.fromString(id) : UUID.randomUUID();
            if (!(u.get("feature") instanceof String feature) || !(u.get("provider") instanceof String provider)
                    || !(u.get("model") instanceof String model)) {
                return Optional.empty();
            }
            return Optional.of(new AiUsage("ai-service:" + callId, userId, feature, provider, model,
                    number(u.get("input_tokens")).longValue(), number(u.get("output_tokens")).longValue(),
                    number(u.get("cost_usd")), number(u.get("latency_ms")).longValue(),
                    u.get("prompt_version") instanceof String v ? v : null,
                    u.get("pricing_version") instanceof String v && !v.isBlank() ? v : null, status));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    /** ai-service sends money as a string and counts as numbers; both are accepted. */
    private static BigDecimal number(Object value) {
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s) {
            return new BigDecimal(s);
        }
        throw new IllegalArgumentException("not a number");
    }

    private static ParseFailure invalidResponse() {
        return ParseFailure.permanent(ParseFailureReason.INVALID_PARSER_RESPONSE, "unexpected ai-service response");
    }
}
