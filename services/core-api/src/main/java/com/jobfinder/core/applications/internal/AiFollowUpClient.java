package com.jobfinder.core.applications.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.jobfinder.core.applications.internal.ApplicationDtos.Length;
import com.jobfinder.core.applications.internal.ApplicationDtos.Tone;
import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.micrometer.observation.ObservationRegistry;

/**
 * Calls ai-service {@code POST /v1/follow-up-email} (docs/adr/0032-application-tracker.md) and checks what comes back
 * before anything is shown.
 *
 * <p>Billing is as for the writing endpoints (ADR 0031): every billed call a response reports goes to the usage ledger
 * under the feature {@code follow_up_email}, {@code SUCCEEDED} when the draft was returned to the user and
 * {@code FAILED} when it was discarded (an error body lists the calls billed before the failure).
 *
 * <p>The checks here are a second line behind ai-service's own: the subject and body must have the agreed shape, no
 * string may contain a placeholder such as {@code [Your Name]}, and a draft whose fact check has a BLOCKING flag (it
 * claims something the resume or the application does not show) is discarded, never shown. The resume, the job text and
 * the notes are personal or third-party data: they are never logged.
 */
@Component
class AiFollowUpClient {

    static final String FEATURE = "follow_up_email";
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    /** ai-service could not be reached or could not do the work (down, no provider key, 5xx, a bad answer). */
    static class AiUnavailableException extends RuntimeException {
        AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The draft made a claim the fact check blocks, so it was discarded. */
    static class RejectedDraftException extends RuntimeException {
        RejectedDraftException(int blocking) {
            super("The draft had " + blocking + " blocking fact-check flag(s)");
        }
    }

    /** A draft that passed every check. */
    record Draft(String model, String promptVersion, String subject, String body) {
    }

    /** The application facts the draft is written from. */
    record Facts(String title, String company, String status, LocalDate appliedOn) {
    }

    /** The placeholder shapes that must never be the text of a draft. */
    static final Pattern PLACEHOLDER = Pattern.compile(
            "\\[[^\\]\\n]{1,80}]|\\{\\{|<[A-Za-z][^<>\\n]{0,60}>|NEEDS_INPUT|\\b(?:your|company)\\s+name\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Logger log = LoggerFactory.getLogger(AiFollowUpClient.class);

    private final RestClient client;
    private final JsonMapper json;
    private final AiUsageLedger ledger;

    AiFollowUpClient(ApplicationsAiProperties ai, ApplicationsProperties properties, JsonMapper json,
            AiUsageLedger ledger, ObservationRegistry observations) {
        this.json = json;
        this.ledger = ledger;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.followUp().connectTimeout()).build());
        factory.setReadTimeout(properties.followUp().readTimeout());
        this.client = RestClient.builder().observationRegistry(observations).baseUrl(ai.baseUrl()).defaultHeader("X-Service-Token", ai.token())
                .requestFactory(factory).build();
    }

    /**
     * @throws AiUnavailableException when nothing usable came back; usage in an error body is recorded first
     * @throws RejectedDraftException when the draft claims something the fact check blocks
     */
    Draft draft(UUID userId, String promptVersion, JsonNode resume, Facts facts, String jobDescription, Tone tone,
            Length length, String notes, Integer yearsExperience, LocalDate asOf) {
        ObjectNode body = json.createObjectNode();
        body.put("user_id", userId.toString());
        body.put("prompt_version", promptVersion);
        body.set("resume", resume);
        ObjectNode application = body.putObject("application");
        application.put("title", facts.title());
        if (facts.company() != null && !facts.company().isBlank()) {
            application.put("company", facts.company());
        }
        application.put("status", facts.status());
        if (facts.appliedOn() != null) {
            application.put("applied_on", facts.appliedOn().toString());
        }
        if (jobDescription != null && !jobDescription.isBlank()) {
            body.put("job_description", jobDescription);
        }
        body.put("tone", tone.wire());
        body.put("length", length.wire());
        if (notes != null) {
            body.put("notes", notes);
        }
        if (yearsExperience != null) {
            body.put("years_experience", yearsExperience);
        }
        body.put("as_of", asOf.toString());
        try {
            return client.post().uri("/v1/follow-up-email").contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .exchange((request, response) -> interpret(userId, promptVersion,
                            response.getStatusCode().value(), response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1)));
        } catch (RestClientException e) {
            throw new AiUnavailableException("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    private Draft interpret(UUID userId, String promptVersion, int status, byte[] bytes) throws IOException {
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new AiUnavailableException("ai-service response too large", null);
        }
        JsonNode response = readTree(bytes);
        if (status != 200) {
            if (response != null) {
                recordUsage(userId, response.get("usage"), AiCallStatus.FAILED);
            }
            String code = response != null && response.path("code").isString() ? response.path("code").asString()
                    : null;
            log.warn("ai-service refused a follow-up request (status={}, code={})", status, code);
            throw new AiUnavailableException("ai-service answered " + status + (code == null ? "" : " " + code),
                    null);
        }
        if (response == null || !promptVersion.equals(response.path("prompt_version").asString(null))) {
            // Output made with another prompt, or not JSON, must never be shown as this draft.
            discard(userId, response);
            throw new AiUnavailableException("unexpected ai-service response", null);
        }
        int blocking = blocking(response.get("fact_check"));
        Optional<Draft> draft = blocking < 0 ? Optional.empty() : parse(response, promptVersion);
        if (draft.isEmpty()) {
            discard(userId, response);
            throw new AiUnavailableException("unexpected ai-service response", null);
        }
        if (blocking > 0) {
            recordUsage(userId, response.get("usage"), AiCallStatus.FAILED);
            throw new RejectedDraftException(blocking);
        }
        recordUsage(userId, response.get("usage"), AiCallStatus.SUCCEEDED);
        return draft.get();
    }

    private void discard(UUID userId, JsonNode response) {
        if (response != null) {
            recordUsage(userId, response.get("usage"), AiCallStatus.FAILED);
        }
    }

    private static Optional<Draft> parse(JsonNode response, String promptVersion) {
        JsonNode subject = response.path("subject");
        JsonNode body = response.path("body");
        if (!subject.isString() || subject.asString().isBlank() || subject.asString().length() > 120
                || CONTROL.matcher(subject.asString()).find() || PLACEHOLDER.matcher(subject.asString()).find()
                || !body.isString() || body.asString().isBlank() || body.asString().length() > 6000
                || PLACEHOLDER.matcher(body.asString()).find()) {
            return Optional.empty();
        }
        String model = response.path("model").isString() ? response.get("model").asString() : null;
        return Optional.of(new Draft(model, promptVersion, subject.asString(), body.asString()));
    }

    /**
     * The number of BLOCKING flags of a fact check that is consistent (the count equals the flags listed and
     * {@code passed} says the same), or -1 when it is not: counts are derived from the flags, not taken on trust.
     */
    private static int blocking(JsonNode check) {
        if (check == null || !check.isObject() || !check.path("passed").isBoolean() || !check.path("blocking").isInt()
                || !check.path("flags").isArray() || check.path("flags").size() > 200) {
            return -1;
        }
        int blocking = 0;
        for (JsonNode flag : check.get("flags")) {
            String severity = flag.path("severity").asString("");
            if (!severity.equals("BLOCKING") && !severity.equals("WARNING")) {
                return -1;
            }
            if (severity.equals("BLOCKING")) {
                blocking++;
            }
        }
        boolean consistent = blocking == check.get("blocking").asInt()
                && check.get("passed").asBoolean() == (blocking == 0);
        return consistent ? blocking : -1;
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
        if (value != null && (value.isNumber() || value.isString())) {
            return new BigDecimal(value.asString());
        }
        throw new IllegalArgumentException("not a number");
    }
}
