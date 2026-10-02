package com.jobfinder.core.interview.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
import com.jobfinder.core.interview.InterviewPrep.BriefClaimView;
import com.jobfinder.core.interview.InterviewPrep.BriefSectionView;
import com.jobfinder.core.interview.InterviewPrep.InterviewQuestionView;
import com.jobfinder.core.jobs.JobForBrief;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls ai-service {@code POST /v1/interview-prep} (docs/adr/0033-interview-prep.md) and checks what comes back before
 * anything is stored.
 *
 * <p>Billing is as for the writing endpoints (ADR 0031): every billed call a response reports (the questions call and
 * the brief call) goes to the usage ledger under the feature {@code interview_prep}, {@code SUCCEEDED} when the result
 * was stored and {@code FAILED} when it was discarded (an error body lists the calls billed before the failure).
 *
 * <p>The checks here are a second line behind ai-service's own grounding check: the questions must be in the agreed
 * categories, every brief claim must name a field that was really sent and that field must contain the quoted
 * evidence, and no string may contain a placeholder. So whatever the service returned, a stored claim always has a
 * source and a quote that are in the job posting or the company record. The resume, the job text and the company
 * fields are personal or third-party data: they are never logged.
 */
@Component
class AiInterviewClient {

    static final String FEATURE = "interview_prep";
    static final int MAX_RESPONSE_BYTES = 512 * 1024;

    /** ai-service could not be reached or could not do the work (down, no provider key, 5xx, a bad answer). */
    static class AiUnavailableException extends RuntimeException {
        AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A prep that passed every check. */
    record Prep(String questionsModel, String briefModel, String promptVersion,
            List<InterviewQuestionView> questions, List<BriefSectionView> sections, List<String> unknowns,
            int droppedClaims, int questionsDropped) {
    }

    /** The placeholder shapes that must never be the text of a stored question or claim. */
    static final Pattern PLACEHOLDER = Pattern.compile(
            "\\[[^\\]\\n]{1,80}]|\\{\\{|<[A-Za-z][^<>\\n]{0,60}>|NEEDS_INPUT|\\b(?:your|company)\\s+name\\b",
            Pattern.CASE_INSENSITIVE);

    static final Set<String> CATEGORIES = Set.of("behavioral", "technical", "role_specific");
    static final Set<String> DIFFICULTIES = Set.of("easy", "medium", "hard");
    static final Set<String> SECTIONS = Set.of("ROLE_OVERVIEW", "COMPANY_FACTS", "SKILLS_AND_TOOLS",
            "LOGISTICS_AND_PAY");

    private static final int MAX_QUESTIONS = 30;
    private static final int MAX_SECTIONS = 8;
    private static final int MAX_CLAIMS = 12;
    private static final int MAX_UNKNOWNS = 25;
    private static final int MAX_DROPPED = 200;
    private static final int MAX_FIELD = 400;
    private static final Logger log = LoggerFactory.getLogger(AiInterviewClient.class);

    private final RestClient client;
    private final InterviewProperties properties;
    private final JsonMapper json;
    private final AiUsageLedger ledger;

    AiInterviewClient(InterviewAiProperties ai, InterviewProperties properties, JsonMapper json,
            AiUsageLedger ledger) {
        this.properties = properties;
        this.json = json;
        this.ledger = ledger;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().baseUrl(ai.baseUrl()).defaultHeader("X-Service-Token", ai.token())
                .requestFactory(factory).build();
    }

    /**
     * The labelled texts of the posting and the company record, keyed by the source names a brief claim may cite. A
     * field with no text is absent. This is both what is sent and what a claim's evidence is checked against.
     */
    Map<String, String> fields(JobForBrief job) {
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "job.title", job.title(), MAX_FIELD);
        put(fields, "job.description", job.descriptionText(), properties.descriptionChars());
        put(fields, "job.location", location(job), MAX_FIELD);
        put(fields, "job.work_mode", job.workMode(), MAX_FIELD);
        put(fields, "job.employment_type", job.employmentType(), MAX_FIELD);
        put(fields, "job.seniority", job.seniority(), MAX_FIELD);
        put(fields, "job.salary", salary(job), MAX_FIELD);
        put(fields, "job.skills", skills(job), MAX_FIELD * 4);
        put(fields, "company.name", job.company(), MAX_FIELD);
        put(fields, "company.domain", job.companyDomain(), MAX_FIELD);
        put(fields, "company.size", job.companySize(), MAX_FIELD);
        put(fields, "company.industry", job.companyIndustry(), MAX_FIELD);
        return fields;
    }

    /**
     * @throws AiUnavailableException when nothing usable came back; usage in an error body is recorded first
     */
    Prep generate(UUID userId, String promptVersion, JsonNode resume, JobForBrief job) {
        Map<String, String> fields = fields(job);
        ObjectNode body = json.createObjectNode();
        body.put("user_id", userId.toString());
        body.put("prompt_version", promptVersion);
        body.set("resume", resume);
        ObjectNode jobNode = body.putObject("job");
        jobNode.put("title", fields.getOrDefault("job.title", job.title()));
        for (String name : List.of("description", "location", "work_mode", "employment_type", "seniority",
                "salary")) {
            if (fields.containsKey("job." + name)) {
                jobNode.put(name, fields.get("job." + name));
            }
        }
        ArrayNode skills = jobNode.putArray("skills");
        job.skills().stream().filter(s -> s != null && !s.isBlank()).map(s -> clip(s.strip(), 100)).limit(60)
                .forEach(skills::add);
        ObjectNode company = body.putObject("company");
        for (String name : List.of("name", "domain", "size", "industry")) {
            if (fields.containsKey("company." + name)) {
                company.put(name, fields.get("company." + name));
            }
        }
        body.put("question_count", properties.questionCount());
        try {
            return client.post().uri("/v1/interview-prep").contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .exchange((request, response) -> interpret(userId, promptVersion, fields,
                            response.getStatusCode().value(), response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1)));
        } catch (RestClientException e) {
            throw new AiUnavailableException("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    private Prep interpret(UUID userId, String promptVersion, Map<String, String> fields, int status, byte[] bytes)
            throws IOException {
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
            log.warn("ai-service refused an interview prep request (status={}, code={})", status, code);
            throw new AiUnavailableException("ai-service answered " + status + (code == null ? "" : " " + code),
                    null);
        }
        Optional<Prep> parsed = response == null ? Optional.empty() : parse(response, promptVersion, fields);
        if (parsed.isEmpty()) {
            // Output made with another prompt, or in another shape, or with a claim the posting does not back,
            // must never be stored as this prep.
            if (response != null) {
                recordUsage(userId, response.get("usage"), AiCallStatus.FAILED);
            }
            throw new AiUnavailableException("unexpected ai-service response", null);
        }
        recordUsage(userId, response.get("usage"), AiCallStatus.SUCCEEDED);
        return parsed.get();
    }

    private Optional<Prep> parse(JsonNode response, String promptVersion, Map<String, String> fields) {
        if (!promptVersion.equals(response.path("prompt_version").asString(null))
                || !response.path("questions_model").isString() || !response.path("brief_model").isString()
                || !response.path("questions_dropped").isInt()) {
            return Optional.empty();
        }
        Optional<List<InterviewQuestionView>> questions = questions(response.get("questions"));
        JsonNode brief = response.path("brief");
        if (questions.isEmpty() || !brief.isObject() || !brief.path("dropped").isArray()
                || brief.path("dropped").size() > MAX_DROPPED) {
            return Optional.empty();
        }
        Optional<List<BriefSectionView>> sections = sections(brief.get("sections"), fields);
        Optional<List<String>> unknowns = unknowns(brief.get("unknowns"));
        if (sections.isEmpty() || unknowns.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Prep(response.get("questions_model").asString(), response.get("brief_model").asString(),
                promptVersion, questions.get(), sections.get(), unknowns.get(), brief.get("dropped").size(),
                response.get("questions_dropped").asInt()));
    }

    private Optional<List<InterviewQuestionView>> questions(JsonNode node) {
        if (node == null || !node.isArray() || node.size() < 3 || node.size() > MAX_QUESTIONS) {
            return Optional.empty();
        }
        List<InterviewQuestionView> out = new ArrayList<>();
        Set<String> categories = new HashSet<>();
        for (JsonNode q : node) {
            String category = q.path("category").asString("");
            String difficulty = q.path("difficulty").asString("");
            if (!CATEGORIES.contains(category) || !DIFFICULTIES.contains(difficulty)
                    || !text(q.path("question"), 300) || !text(q.path("rationale"), 300)) {
                return Optional.empty();
            }
            categories.add(category);
            out.add(new InterviewQuestionView(category, q.get("question").asString(), q.get("rationale").asString(),
                    difficulty));
        }
        return categories.equals(CATEGORIES) ? Optional.of(out) : Optional.empty();
    }

    private Optional<List<BriefSectionView>> sections(JsonNode node, Map<String, String> fields) {
        if (node == null || !node.isArray() || node.size() > MAX_SECTIONS) {
            return Optional.empty();
        }
        List<BriefSectionView> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode s : node) {
            String id = s.path("id").asString("");
            if (!SECTIONS.contains(id) || !ids.add(id) || !text(s.path("title"), 100) || !s.path("claims").isArray()
                    || s.path("claims").isEmpty() || s.path("claims").size() > MAX_CLAIMS) {
                return Optional.empty();
            }
            List<BriefClaimView> claims = new ArrayList<>();
            for (JsonNode c : s.get("claims")) {
                String source = c.path("source").asString("");
                // The source must be a field that was sent, and it must contain the quote: a claim that does not
                // meet both is not grounded, whatever ai-service said.
                if (!text(c.path("statement"), 300) || !text(c.path("evidence"), 300) || !fields.containsKey(source)
                        || !contains(fields.get(source), c.get("evidence").asString())) {
                    return Optional.empty();
                }
                claims.add(new BriefClaimView(c.get("statement").asString(), source, c.get("evidence").asString()));
            }
            out.add(new BriefSectionView(id, s.get("title").asString(), claims));
        }
        return Optional.of(out);
    }

    private static Optional<List<String>> unknowns(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > MAX_UNKNOWNS) {
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode u : node) {
            if (!text(u, 300)) {
                return Optional.empty();
            }
            out.add(u.asString());
        }
        return Optional.of(out);
    }

    private static boolean text(JsonNode value, int max) {
        return value.isString() && !value.asString().isBlank() && value.asString().length() <= max
                && !PLACEHOLDER.matcher(value.asString()).find();
    }

    // --- grounding, second line ---

    /** Case, accent and punctuation folded, the way ai-service folds before it compares words. */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replace("ß", "ss").replace("&", " and ")
                .replaceAll("[^a-z0-9+#]+", " ").strip();
    }

    /** Does {@code field} contain {@code evidence} as a run of whole words? Nothing but case, accents and punctuation is ignored. */
    static boolean contains(String field, String evidence) {
        String needle = fold(evidence);
        return !needle.isEmpty() && (" " + fold(field) + " ").contains(" " + needle + " ");
    }

    // --- the fields sent ---

    private static void put(Map<String, String> fields, String name, String value, int max) {
        if (value != null && !value.isBlank()) {
            fields.put(name, clip(value.strip(), max));
        }
    }

    private static String location(JobForBrief job) {
        if (job.locationRaw() != null && !job.locationRaw().isBlank()) {
            return job.locationRaw();
        }
        String joined = java.util.stream.Stream.of(job.city(), job.country()).filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    /** The structured salary as one line a claim can quote, for example {@code NGN 500000 to 800000 per YEAR}. */
    private static String salary(JobForBrief job) {
        if (job.salaryMin() == null && job.salaryMax() == null) {
            return null;
        }
        String range = job.salaryMin() != null && job.salaryMax() != null
                && job.salaryMin().compareTo(job.salaryMax()) != 0
                        ? plain(job.salaryMin()) + " to " + plain(job.salaryMax())
                        : plain(job.salaryMin() != null ? job.salaryMin() : job.salaryMax());
        return (job.salaryCurrency() == null ? "" : job.salaryCurrency() + " ") + range
                + (job.salaryPeriod() == null ? "" : " per " + job.salaryPeriod());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String skills(JobForBrief job) {
        List<String> skills = job.skills().stream().filter(s -> s != null && !s.isBlank()).map(s -> clip(s.strip(), 100))
                .limit(60).toList();
        return skills.isEmpty() ? null : String.join(", ", skills);
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // --- usage ---

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
