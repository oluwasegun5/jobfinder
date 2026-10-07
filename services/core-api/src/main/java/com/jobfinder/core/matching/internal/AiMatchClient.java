package com.jobfinder.core.matching.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.micrometer.observation.ObservationRegistry;

/**
 * Calls ai-service {@code POST /v1/score-matches} (docs/adr/0026-matching-engine.md) and turns the answer into one
 * {@link JobOutcome} per job asked about.
 *
 * <p>Every billed call the response reports goes to the usage ledger as feature {@code match_scoring}, against the
 * user the request was made for, on success and on failure alike (an error body lists the calls billed before the
 * failure). A call is recorded {@code FAILED} when the response scored no job at all (its output was discarded) and
 * {@code SUCCEEDED} otherwise: ai-service does not say which of several calls in one response failed. If the ledger
 * itself is down, the figures are logged at ERROR with the {@code UNRECORDED} marker of ADR 0025 and the scores are
 * still used: the work was paid for and is good.
 *
 * <p>The candidate and job text is personal or third-party data: it is never logged, and the answer is checked
 * (score range, reason lengths) before anything is stored.
 */
@Component
class AiMatchClient {

    /** What came back for one job; {@code scored} jobs carry a score and reasons, the others an error code. */
    record JobOutcome(UUID jobId, boolean scored, int score, List<String> strengths, List<String> gaps,
            String errorCode) {

        static JobOutcome failed(UUID jobId, String code) {
            return new JobOutcome(jobId, false, 0, List.of(), List.of(), code);
        }
    }

    /** The outcomes, one per job asked about and in that order, and the model that scored them (null if none did). */
    record Scored(String model, List<JobOutcome> outcomes) {
    }

    /** ai-service could not be reached or could not score at all (down, rate limited, no provider key, 5xx). */
    static class AiUnavailableException extends RuntimeException {
        AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final String FEATURE = "match_scoring";

    private static final Logger log = LoggerFactory.getLogger(AiMatchClient.class);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REASONS = 5;
    private static final int MAX_REASON_CHARS = 400;

    private final RestClient client;
    private final JsonMapper json;
    private final AiUsageLedger ledger;

    AiMatchClient(MatchAiServiceProperties properties, MatchingProperties matching, JsonMapper json,
            AiUsageLedger ledger, ObservationRegistry observations) {
        this.ledger = ledger;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(matching.llm().connectTimeout()).build());
        factory.setReadTimeout(matching.llm().readTimeout());
        this.client = RestClient.builder().baseUrl(properties.baseUrl())
                // The observation injects the trace context, so ai-service's spans join this request's trace (ADR 0039).
                .observationRegistry(observations)
                .defaultHeader("X-Service-Token", properties.token()).requestFactory(factory).build();
    }

    /**
     * Scores the jobs for the candidate. The result has an outcome for every job in {@code jobs}, in order.
     *
     * @throws AiUnavailableException when nothing could be scored; usage in an error body is recorded first
     */
    Scored score(UUID userId, String promptVersion, ObjectNode candidate, List<ObjectNode> jobs) {
        ObjectNode body = json.createObjectNode();
        body.put("user_id", userId.toString());
        body.put("prompt_version", promptVersion);
        body.set("candidate", candidate);
        ArrayNode array = body.putArray("jobs");
        jobs.forEach(array::add);
        List<UUID> asked = jobs.stream().map(j -> UUID.fromString(j.get("id").asString())).toList();
        try {
            return client.post().uri("/v1/score-matches").contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .exchange((request, response) -> interpret(userId, promptVersion, asked,
                            response.getStatusCode().value(), response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1)));
        } catch (RestClientException e) {
            throw new AiUnavailableException("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    private Scored interpret(UUID userId, String promptVersion, List<UUID> asked, int status, byte[] body)
            throws IOException {
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new AiUnavailableException("ai-service response too large", null);
        }
        Map<String, Object> response = readObject(body);
        if (status != 200) {
            if (response != null) {
                recordUsage(userId, response.get("usage"), AiCallStatus.FAILED);
            }
            String code = response != null && response.get("code") instanceof String c ? c : null;
            log.warn("ai-service refused a match-scoring request (status={}, code={})", status, code);
            throw new AiUnavailableException("ai-service answered " + status + (code == null ? "" : " " + code),
                    null);
        }
        if (response == null || !promptVersion.equals(response.get("prompt_version"))
                || !(response.get("results") instanceof List<?> results)) {
            // Scores made with another prompt must never be stored under this prompt's key.
            if (response != null) {
                recordUsage(userId, response.get("usage"), AiCallStatus.FAILED);
            }
            throw new AiUnavailableException("unexpected ai-service response", null);
        }
        Map<UUID, JobOutcome> byJob = new java.util.HashMap<>();
        for (Object item : results) {
            if (item instanceof Map<?, ?> result) {
                parse(result).ifPresent(o -> byJob.putIfAbsent(o.jobId(), o));
            }
        }
        recordUsage(userId, response.get("usage"),
                byJob.values().stream().anyMatch(JobOutcome::scored) ? AiCallStatus.SUCCEEDED : AiCallStatus.FAILED);
        List<JobOutcome> outcomes = new ArrayList<>();
        for (UUID id : asked) {
            outcomes.add(byJob.getOrDefault(id, JobOutcome.failed(id, "llm_output_incomplete")));
        }
        return new Scored(response.get("model") instanceof String m && !m.isBlank() ? m : null, outcomes);
    }

    private static Optional<JobOutcome> parse(Map<?, ?> result) {
        UUID jobId;
        try {
            jobId = UUID.fromString((String) result.get("job_id"));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!"scored".equals(result.get("status"))) {
            String code = result.get("error_code") instanceof String c && c.length() <= 60 ? c : "llm_failed";
            return Optional.of(JobOutcome.failed(jobId, code));
        }
        if (!(result.get("score") instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.intValue() < 0 || n.intValue() > 100) {
            return Optional.of(JobOutcome.failed(jobId, "invalid_score"));
        }
        List<String> strengths = reasons(result.get("strengths"));
        List<String> gaps = reasons(result.get("gaps"));
        if (strengths == null || gaps == null) {
            return Optional.of(JobOutcome.failed(jobId, "invalid_reasons"));
        }
        return Optional.of(new JobOutcome(jobId, true, n.intValue(), strengths, gaps, null));
    }

    /** Up to five non-blank strings of bounded length, or null if the shape is wrong. */
    private static List<String> reasons(Object raw) {
        if (!(raw instanceof List<?> items) || items.size() > MAX_REASONS) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String s) || s.isBlank() || s.length() > MAX_REASON_CHARS) {
                return null;
            }
            out.add(s.strip());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(byte[] body) {
        try {
            return json.readValue(body, Map.class);
        } catch (JacksonException e) {
            return null;
        }
    }

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
            if (!(u.get("provider") instanceof String provider) || !(u.get("model") instanceof String model)) {
                return Optional.empty();
            }
            // The ledger's feature is ours, whatever label the service puts on its calls.
            return Optional.of(new AiUsage("ai-service:" + callId, userId, FEATURE, provider, model,
                    number(u.get("input_tokens")).longValue(), number(u.get("output_tokens")).longValue(),
                    number(u.get("cost_usd")), number(u.get("latency_ms")).longValue(),
                    u.get("prompt_version") instanceof String v ? v : null,
                    u.get("pricing_version") instanceof String v && !v.isBlank() ? v : null, status));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static BigDecimal number(Object value) {
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s) {
            return new BigDecimal(s);
        }
        throw new IllegalArgumentException("not a number");
    }
}
