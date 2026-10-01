package com.jobfinder.core.documents.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import com.jobfinder.core.documents.internal.DocumentDtos.Change;
import com.jobfinder.core.documents.internal.DocumentDtos.ChangeState;
import com.jobfinder.core.documents.internal.DocumentDtos.FactCheck;
import com.jobfinder.core.documents.internal.DocumentDtos.Flag;
import com.jobfinder.core.documents.internal.DocumentDtos.TailorOptions;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls ai-service {@code POST /v1/tailor-resume} and {@code POST /v1/fact-check}
 * (docs/adr/0029-resume-tailoring.md) and checks what comes back before anything is stored.
 *
 * <p>Every billed call a tailoring response reports goes to the usage ledger as feature {@code tailor_resume}, against
 * the user the request was made for, on success and on failure alike (an error body lists the calls billed before the
 * failure). A call is recorded {@code SUCCEEDED} when its output was used, {@code FAILED} when it was discarded
 * (an error, or an answer that is not the agreed shape). If the ledger is down the figures are logged at ERROR with
 * the {@code UNRECORDED} marker of ADR 0025 and the result is still used. The fact check has no model behind it:
 * no usage, no cap.
 *
 * <p>The resume and the job text are personal or third-party data: they are never logged.
 */
@Component
class AiTailoringClient {

    /** What a tailoring call returned, checked: the resume, the changes (all ACCEPTED) and the fact check. */
    record Tailored(String model, String promptVersion, JsonNode resume, List<Change> changes, FactCheck factCheck) {
    }

    /** ai-service could not be reached or could not do the work (down, no provider key, 5xx, a bad answer). */
    static class AiUnavailableException extends RuntimeException {
        AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** ai-service refused the resume it was sent for the fact check (it is not a valid resume). */
    static class InvalidContentException extends RuntimeException {
        InvalidContentException() {
            super("ai-service rejected the resume content");
        }
    }

    static final String FEATURE = "tailor_resume";

    private static final Logger log = LoggerFactory.getLogger(AiTailoringClient.class);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Set<String> SECTIONS = Set.of("HEADLINE", "SUMMARY", "EXPERIENCE", "EDUCATION", "SKILLS",
            "PROJECTS", "CERTIFICATIONS");
    private static final Set<String> OPS = Set.of("REPLACE", "ADD", "REMOVE");
    private static final Set<String> SEVERITIES = Set.of("BLOCKING", "WARNING");
    private static final int MAX_CHANGES = 120;
    private static final int MAX_FLAGS = 200;

    private final RestClient tailoringClient;
    private final RestClient factCheckClient;
    private final JsonMapper json;
    private final AiUsageLedger ledger;

    AiTailoringClient(DocumentsAiProperties properties, DocumentsProperties documents, JsonMapper json,
            AiUsageLedger ledger) {
        this.json = json;
        this.ledger = ledger;
        this.tailoringClient = client(properties, documents.tailoring().connectTimeout(),
                documents.tailoring().readTimeout());
        this.factCheckClient = client(properties, documents.factCheck().connectTimeout(),
                documents.factCheck().readTimeout());
    }

    private static RestClient client(DocumentsAiProperties properties, Duration connect, Duration read) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connect).build());
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(properties.baseUrl())
                .defaultHeader("X-Service-Token", properties.token()).requestFactory(factory).build();
    }

    /**
     * Tailors {@code source} to the job.
     *
     * @throws AiUnavailableException when nothing usable came back; usage in an error body is recorded first
     */
    Tailored tailor(UUID userId, String promptVersion, JsonNode source, String jobTitle, String jobCompany,
            String jobDescription, TailorOptions options) {
        ObjectNode body = json.createObjectNode();
        body.put("user_id", userId.toString());
        body.put("prompt_version", promptVersion);
        body.set("resume", source);
        ObjectNode job = body.putObject("job");
        job.put("title", jobTitle);
        if (jobCompany != null && !jobCompany.isBlank()) {
            job.put("company", jobCompany);
        }
        if (jobDescription != null && !jobDescription.isBlank()) {
            job.put("description", jobDescription);
        }
        ObjectNode opts = body.putObject("options");
        if (options != null && options.rewriteSummary() != null) {
            opts.put("rewrite_summary", options.rewriteSummary());
        }
        if (options != null && options.maxBulletsPerRole() != null) {
            opts.put("max_bullets_per_role", options.maxBulletsPerRole());
        }
        try {
            return tailoringClient.post().uri("/v1/tailor-resume").contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .exchange((request, response) -> interpret(userId, promptVersion, response.getStatusCode().value(),
                            response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1)));
        } catch (RestClientException e) {
            throw new AiUnavailableException("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    private Tailored interpret(UUID userId, String promptVersion, int status, byte[] body) throws IOException {
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new AiUnavailableException("ai-service response too large", null);
        }
        JsonNode response = readTree(body);
        if (status != 200) {
            if (response != null) {
                recordUsage(userId, response.get("usage"), AiCallStatus.FAILED);
            }
            String code = response != null && response.path("code").isString() ? response.path("code").asString() : null;
            log.warn("ai-service refused a tailoring request (status={}, code={})", status, code);
            throw new AiUnavailableException("ai-service answered " + status + (code == null ? "" : " " + code),
                    null);
        }
        Optional<Tailored> parsed = response == null ? Optional.empty() : parse(response, promptVersion);
        if (parsed.isEmpty()) {
            // Output made with another prompt, or in another shape, must never be stored as this draft.
            if (response != null) {
                recordUsage(userId, response.get("usage"), AiCallStatus.FAILED);
            }
            throw new AiUnavailableException("unexpected ai-service response", null);
        }
        recordUsage(userId, response.get("usage"), AiCallStatus.SUCCEEDED);
        return parsed.get();
    }

    private Optional<Tailored> parse(JsonNode response, String promptVersion) {
        if (!promptVersion.equals(response.path("prompt_version").asString(null))
                || !response.path("resume").isObject() || !response.path("changes").isArray()
                || response.path("changes").size() > MAX_CHANGES) {
            return Optional.empty();
        }
        Optional<FactCheck> factCheck = factCheck(response.get("fact_check"));
        if (factCheck.isEmpty()) {
            return Optional.empty();
        }
        List<Change> changes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : response.get("changes")) {
            JsonNode id = node.path("id");
            JsonNode rationale = node.path("rationale");
            if (!node.isObject() || !id.isString() || id.asString().length() > 20 || !ids.add(id.asString())
                    || !SECTIONS.contains(node.path("section").asString(""))
                    || !OPS.contains(node.path("op").asString(""))
                    || !node.path("path").isString() || node.path("path").asString().length() > 60
                    || !rationale.isString() || rationale.asString().length() > 300) {
                return Optional.empty();
            }
            changes.add(new Change(id.asString(), node.get("section").asString(), node.get("op").asString(),
                    node.get("path").asString(), nullable(node.get("before")), nullable(node.get("after")),
                    rationale.asString(), ChangeState.ACCEPTED, false));
        }
        String model = response.path("model").isString() ? response.get("model").asString() : null;
        return Optional.of(new Tailored(model, promptVersion, response.get("resume"), changes, factCheck.get()));
    }

    /** The deterministic fact check of {@code candidate} against {@code source}; no model, no cost. */
    FactCheck factCheck(JsonNode source, JsonNode candidate, String jobDescription) {
        ObjectNode body = json.createObjectNode();
        body.set("source", source);
        body.set("candidate", candidate);
        if (jobDescription != null && !jobDescription.isBlank()) {
            body.put("job_description", jobDescription);
        }
        try {
            return factCheckClient.post().uri("/v1/fact-check").contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body)).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (status == 422) {
                            throw new InvalidContentException();
                        }
                        JsonNode tree = bytes.length > MAX_RESPONSE_BYTES ? null : readTree(bytes);
                        Optional<FactCheck> parsed = status == 200 && tree != null ? factCheck(tree)
                                : Optional.empty();
                        return parsed.orElseThrow(() -> new AiUnavailableException(
                                "ai-service fact check answered " + status, null));
                    });
        } catch (RestClientException e) {
            throw new AiUnavailableException("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    private Optional<FactCheck> factCheck(JsonNode node) {
        if (node == null || !node.isObject() || !node.path("passed").isBoolean() || !node.path("blocking").isInt()
                || !node.path("warnings").isInt() || !node.path("flags").isArray()
                || node.path("flags").size() > MAX_FLAGS) {
            return Optional.empty();
        }
        List<Flag> flags = new ArrayList<>();
        int blocking = 0;
        for (JsonNode f : node.get("flags")) {
            if (!f.path("code").isString() || f.path("code").asString().length() > 40
                    || !SEVERITIES.contains(f.path("severity").asString(""))
                    || !f.path("value").isString() || !f.path("message").isString()
                    || (!f.path("path").isNull() && !f.path("path").isMissingNode() && !f.path("path").isString())) {
                return Optional.empty();
            }
            if ("BLOCKING".equals(f.get("severity").asString())) {
                blocking++;
            }
            flags.add(new Flag(f.get("code").asString(), f.get("severity").asString(),
                    f.path("path").isString() ? f.get("path").asString() : null,
                    clip(f.get("value").asString(), 200), clip(f.get("message").asString(), 300)));
        }
        // The counts are derived from the flags, not taken on trust: approval depends on them.
        boolean consistent = blocking == node.get("blocking").asInt()
                && node.get("passed").asBoolean() == (blocking == 0);
        if (!consistent) {
            return Optional.empty();
        }
        return Optional.of(new FactCheck(blocking == 0, blocking, flags.size() - blocking, flags,
                node.path("checker_version").asString("")));
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static JsonNode nullable(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private JsonNode readTree(byte[] body) {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private void recordUsage(UUID userId, JsonNode usage, AiCallStatus status) {
        if (usage == null || !usage.isArray()) {
            return;
        }
        for (JsonNode call : usage) {
            Optional<AiUsage> parsed = toUsage(userId, call, status);
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

    private static Optional<AiUsage> toUsage(UUID userId, JsonNode u, AiCallStatus status) {
        try {
            UUID callId = u.path("call_id").isString() ? UUID.fromString(u.get("call_id").asString())
                    : UUID.randomUUID();
            if (!u.path("provider").isString() || !u.path("model").isString()) {
                return Optional.empty();
            }
            // The ledger's feature is ours, whatever label the service puts on its calls.
            return Optional.of(new AiUsage("ai-service:" + callId, userId, FEATURE, u.get("provider").asString(),
                    u.get("model").asString(), number(u.get("input_tokens")).longValue(),
                    number(u.get("output_tokens")).longValue(), number(u.get("cost_usd")),
                    number(u.get("latency_ms")).longValue(),
                    u.path("prompt_version").isString() ? u.get("prompt_version").asString() : null,
                    u.path("pricing_version").isString() && !u.get("pricing_version").asString().isBlank()
                            ? u.get("pricing_version").asString() : null,
                    status));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static BigDecimal number(JsonNode value) {
        if (value != null && value.isNumber()) {
            return new BigDecimal(value.asString());
        }
        if (value != null && value.isString()) {
            return new BigDecimal(value.asString());
        }
        throw new IllegalArgumentException("not a number");
    }
}
