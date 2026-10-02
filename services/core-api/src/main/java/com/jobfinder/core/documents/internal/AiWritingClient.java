package com.jobfinder.core.documents.internal;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.documents.internal.AiTailoringClient.AiUnavailableException;
import com.jobfinder.core.documents.internal.AiTailoringClient.InvalidContentException;
import com.jobfinder.core.documents.internal.DocumentDtos.FactCheck;
import com.jobfinder.core.documents.internal.WritingDtos.Length;
import com.jobfinder.core.documents.internal.WritingDtos.Tone;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls ai-service {@code POST /v1/cover-letter}, {@code /v1/screening-answers} and {@code /v1/fact-check-text}
 * (docs/adr/0031-cover-letters-and-application-pack.md) and checks what comes back before anything is stored.
 *
 * <p>Billing is as for tailoring (ADR 0029): every billed call a response reports goes to the usage ledger under the
 * feature {@code cover_letter} or {@code screening_answers}, {@code SUCCEEDED} when its output was used and
 * {@code FAILED} when it was discarded (an error body lists the calls billed before the failure). The text fact check
 * has no model behind it: no usage, no cap.
 *
 * <p>The checks here are a second line behind ai-service's own: the prose must have the agreed shape and lengths, the
 * BLOCKING count must equal the BLOCKING flags listed, and no string may contain a placeholder, so a stored letter can
 * never carry {@code [Your Name]} or an unanswered {@code NEEDS_INPUT} marker as its text whatever the service
 * returned. The resume, the job text and the notes are personal or third-party data: they are never logged.
 */
@Component
class AiWritingClient {

    static final String LETTER_FEATURE = "cover_letter";
    static final String ANSWERS_FEATURE = "screening_answers";

    /** What a generation returned, checked: the document content (shape of {@code content} for its type). */
    record Written(String model, String promptVersion, JsonNode content, FactCheck factCheck) {
    }

    /** One text to fact-check: {@code path} is how a flag names it. */
    record TextItem(String path, String text) {
    }

    /** What core-api tells the writer about the candidate, beyond the resume (only what the profile really has). */
    record ProfileFacts(Integer yearsExperience, List<String> locations, List<String> workModes, Integer minSalary,
            String currency, boolean needsSponsorship) {
    }

    /** The placeholder shapes that must never be the text of a stored document. */
    static final Pattern PLACEHOLDER = Pattern.compile(
            "\\[[^\\]\\n]{1,80}]|\\{\\{|<[A-Za-z][^<>\\n]{0,60}>|NEEDS_INPUT|\\b(?:your|company)\\s+name\\b",
            Pattern.CASE_INSENSITIVE);

    static final List<String> ANSWER_IDS = List.of("WHY_COMPANY_ROLE", "STRENGTHS", "GROWTH_AREA",
            "BIGGEST_ACHIEVEMENT", "NOTICE_PERIOD", "SALARY_EXPECTATION", "WORK_AUTHORIZATION", "RELOCATION_REMOTE",
            "YEARS_KEY_SKILLS", "HOW_HEARD");
    static final Set<String> ANSWER_STATUSES = Set.of("GENERATED", "FROM_PROFILE", "NEEDS_INPUT");

    private static final Logger log = LoggerFactory.getLogger(AiWritingClient.class);
    private static final int MAX_PARAGRAPHS = 8;

    private final RestClient client;
    private final RestClient factCheckClient;
    private final AiTailoringClient shared;
    private final JsonMapper json;

    AiWritingClient(DocumentsAiProperties properties, DocumentsProperties documents, AiTailoringClient shared,
            JsonMapper json) {
        this.shared = shared;
        this.json = json;
        this.client = AiTailoringClient.client(properties, documents.tailoring().connectTimeout(),
                documents.tailoring().readTimeout());
        this.factCheckClient = AiTailoringClient.client(properties, documents.factCheck().connectTimeout(),
                documents.factCheck().readTimeout());
    }

    // --- cover letter ---

    Written coverLetter(UUID userId, String promptVersion, JsonNode resume, String jobTitle, String jobCompany,
            String jobDescription, Tone tone, Length length, String notes, Integer yearsExperience, LocalDate asOf) {
        ObjectNode body = json.createObjectNode();
        body.put("user_id", userId.toString());
        body.put("prompt_version", promptVersion);
        body.set("resume", resume);
        job(body.putObject("job"), jobTitle, jobCompany, jobDescription);
        body.put("tone", tone.wire());
        body.put("length", length.wire());
        if (notes != null) {
            body.put("notes", notes);
        }
        if (yearsExperience != null) {
            body.put("years_experience", yearsExperience);
        }
        body.put("as_of", asOf.toString());
        return call("/v1/cover-letter", LETTER_FEATURE, userId, promptVersion, body,
                response -> letterContent(response, jobTitle, jobCompany));
    }

    private Optional<JsonNode> letterContent(JsonNode response, String jobTitle, String jobCompany) {
        JsonNode letter = response.path("letter");
        JsonNode sender = response.path("sender");
        if (!letter.isObject() || !sender.isObject() || !text(letter, "salutation", 120)
                || !text(letter, "closing", 60) || !letter.path("paragraphs").isArray()
                || letter.path("paragraphs").isEmpty() || letter.path("paragraphs").size() > MAX_PARAGRAPHS) {
            return Optional.empty();
        }
        ArrayNode paragraphs = json.createArrayNode();
        for (JsonNode p : letter.get("paragraphs")) {
            if (!p.isString() || p.asString().isBlank() || p.asString().length() > 1600
                    || PLACEHOLDER.matcher(p.asString()).find()) {
                return Optional.empty();
            }
            paragraphs.add(p.asString());
        }
        if (PLACEHOLDER.matcher(letter.get("salutation").asString()).find()
                || PLACEHOLDER.matcher(letter.get("closing").asString()).find()) {
            return Optional.empty();
        }
        ObjectNode content = json.createObjectNode();
        ObjectNode senderOut = content.putObject("sender");
        for (String field : List.of("full_name", "email", "phone", "location")) {
            if (sender.path(field).isString() && sender.get(field).asString().length() <= 200) {
                senderOut.put(field, sender.get(field).asString());
            }
        }
        ArrayNode links = senderOut.putArray("links");
        for (JsonNode link : sender.path("links")) {
            if (link.path("url").isString() && link.get("url").asString().length() <= 500) {
                ObjectNode out = links.addObject();
                out.put("url", link.get("url").asString());
                if (link.path("label").isString()) {
                    out.put("label", AiTailoringClient.clip(link.get("label").asString(), 200));
                }
            }
        }
        ObjectNode recipient = content.putObject("recipient");
        recipient.put("job_title", jobTitle);
        if (jobCompany != null) {
            recipient.put("company", jobCompany);
        }
        content.put("salutation", letter.get("salutation").asString());
        content.set("paragraphs", paragraphs);
        content.put("closing", letter.get("closing").asString());
        if (letter.path("signature").isString() && letter.get("signature").asString().length() <= 200) {
            content.put("signature", letter.get("signature").asString());
        }
        return Optional.of(content);
    }

    // --- screening answers ---

    Written screeningAnswers(UUID userId, String promptVersion, JsonNode resume, String jobTitle, String jobCompany,
            String jobDescription, List<String> jobSkills, ProfileFacts profile, Tone tone, Length length,
            String notes, LocalDate asOf) {
        ObjectNode body = json.createObjectNode();
        body.put("user_id", userId.toString());
        body.put("prompt_version", promptVersion);
        body.set("resume", resume);
        ObjectNode job = body.putObject("job");
        job(job, jobTitle, jobCompany, jobDescription);
        ArrayNode skills = job.putArray("skills");
        jobSkills.stream().filter(s -> s != null && !s.isBlank()).map(s -> AiTailoringClient.clip(s.strip(), 100))
                .limit(60).forEach(skills::add);
        ObjectNode facts = body.putObject("profile");
        if (profile.yearsExperience() != null) {
            facts.put("years_experience", profile.yearsExperience());
        }
        ObjectNode prefs = facts.putObject("preferences");
        ArrayNode locations = prefs.putArray("locations");
        profile.locations().stream().map(s -> AiTailoringClient.clip(s, 100)).limit(20).forEach(locations::add);
        ArrayNode modes = prefs.putArray("work_modes");
        profile.workModes().forEach(modes::add);
        if (profile.minSalary() != null) {
            prefs.put("min_salary", profile.minSalary());
        }
        if (profile.currency() != null && !profile.currency().isBlank()) {
            prefs.put("currency", profile.currency().strip());
        }
        // Only "yes" is a statement; the preference's default (false) means "not stated", so it is not sent.
        if (profile.needsSponsorship()) {
            prefs.put("needs_sponsorship", true);
        }
        body.put("tone", tone.wire());
        body.put("length", length.wire());
        if (notes != null) {
            body.put("notes", notes);
        }
        body.put("as_of", asOf.toString());
        return call("/v1/screening-answers", ANSWERS_FEATURE, userId, promptVersion, body, this::answersContent);
    }

    private Optional<JsonNode> answersContent(JsonNode response) {
        JsonNode answers = response.path("answers");
        if (!answers.isArray() || answers.size() != ANSWER_IDS.size()) {
            return Optional.empty();
        }
        ObjectNode content = json.createObjectNode();
        ArrayNode out = content.putArray("answers");
        Set<String> seen = new HashSet<>();
        for (JsonNode a : answers) {
            String id = a.path("id").asString("");
            String status = a.path("status").asString("");
            if (!ANSWER_IDS.contains(id) || !seen.add(id) || !ANSWER_STATUSES.contains(status)
                    || !a.path("question").isString() || a.get("question").asString().length() > 300
                    || !a.path("answer").isString() || a.get("answer").asString().length() > 1200
                    || PLACEHOLDER.matcher(a.get("answer").asString()).find()) {
                return Optional.empty();
            }
            boolean needsInput = "NEEDS_INPUT".equals(status);
            // A written or profile answer has text; an unanswered one has none, and says what is needed instead.
            if (needsInput != a.get("answer").asString().isBlank()) {
                return Optional.empty();
            }
            ObjectNode item = out.addObject();
            item.put("id", id);
            item.put("question", a.get("question").asString());
            item.put("answer", a.get("answer").asString());
            item.put("status", status);
            if (a.path("hint").isString() && !a.get("hint").asString().isBlank()) {
                item.put("hint", AiTailoringClient.clip(a.get("hint").asString(), 300));
            } else if (needsInput) {
                return Optional.empty();
            }
        }
        return Optional.of(content);
    }

    // --- text fact check ---

    /** The deterministic fact check of prose against the resume; no model, no cost. */
    FactCheck factCheckText(JsonNode source, List<TextItem> items, String jobDescription, String allowedContext,
            Integer yearsExperience, LocalDate asOf) {
        ObjectNode body = json.createObjectNode();
        body.set("source", source);
        ArrayNode texts = body.putArray("texts");
        for (TextItem item : items) {
            ObjectNode t = texts.addObject();
            t.put("path", item.path());
            t.put("text", item.text());
        }
        if (jobDescription != null && !jobDescription.isBlank()) {
            body.put("job_description", jobDescription);
        }
        if (allowedContext != null && !allowedContext.isBlank()) {
            body.put("allowed_context", AiTailoringClient.clip(allowedContext, 4000));
        }
        if (yearsExperience != null) {
            body.put("years_experience", yearsExperience);
        }
        body.put("as_of", asOf.toString());
        try {
            return factCheckClient.post().uri("/v1/fact-check-text").contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body)).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] bytes = response.getBody().readNBytes(AiTailoringClient.MAX_RESPONSE_BYTES + 1);
                        if (status == 422) {
                            throw new InvalidContentException();
                        }
                        JsonNode tree = bytes.length > AiTailoringClient.MAX_RESPONSE_BYTES ? null
                                : shared.readTree(bytes);
                        Optional<FactCheck> parsed = status == 200 && tree != null ? shared.parseFactCheck(tree)
                                : Optional.empty();
                        return parsed.orElseThrow(() -> new AiUnavailableException(
                                "ai-service text fact check answered " + status, null));
                    });
        } catch (RestClientException e) {
            throw new AiUnavailableException("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    // --- plumbing ---

    private static void job(ObjectNode job, String title, String company, String description) {
        job.put("title", title);
        if (company != null && !company.isBlank()) {
            job.put("company", company);
        }
        if (description != null && !description.isBlank()) {
            job.put("description", description);
        }
    }

    private static boolean text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() && value.asString().length() <= max;
    }

    private interface Content {
        Optional<JsonNode> of(JsonNode response);
    }

    private Written call(String path, String feature, UUID userId, String promptVersion, ObjectNode body,
            Content content) {
        try {
            return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .exchange((request, response) -> interpret(feature, userId, promptVersion,
                            response.getStatusCode().value(),
                            response.getBody().readNBytes(AiTailoringClient.MAX_RESPONSE_BYTES + 1), content));
        } catch (RestClientException e) {
            throw new AiUnavailableException("ai-service unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    private Written interpret(String feature, UUID userId, String promptVersion, int status, byte[] body,
            Content content) throws IOException {
        if (body.length > AiTailoringClient.MAX_RESPONSE_BYTES) {
            throw new AiUnavailableException("ai-service response too large", null);
        }
        JsonNode response = shared.readTree(body);
        if (status != 200) {
            if (response != null) {
                shared.recordUsage(userId, response.get("usage"), AiCallStatus.FAILED, feature);
            }
            String code = response != null && response.path("code").isString() ? response.path("code").asString()
                    : null;
            log.warn("ai-service refused a {} request (status={}, code={})", feature, status, code);
            throw new AiUnavailableException("ai-service answered " + status + (code == null ? "" : " " + code),
                    null);
        }
        Optional<Written> parsed = response == null ? Optional.empty() : parse(response, promptVersion, content);
        if (parsed.isEmpty()) {
            // Output made with another prompt, or in another shape, must never be stored as this document.
            if (response != null) {
                shared.recordUsage(userId, response.get("usage"), AiCallStatus.FAILED, feature);
            }
            throw new AiUnavailableException("unexpected ai-service response", null);
        }
        shared.recordUsage(userId, response.get("usage"), AiCallStatus.SUCCEEDED, feature);
        return parsed.get();
    }

    private Optional<Written> parse(JsonNode response, String promptVersion, Content content) {
        if (!promptVersion.equals(response.path("prompt_version").asString(null))) {
            return Optional.empty();
        }
        Optional<FactCheck> factCheck = shared.parseFactCheck(response.get("fact_check"));
        Optional<JsonNode> body = content.of(response);
        if (factCheck.isEmpty() || body.isEmpty()) {
            return Optional.empty();
        }
        String model = response.path("model").isString() ? response.get("model").asString() : null;
        return Optional.of(new Written(model, promptVersion, body.get(), factCheck.get()));
    }
}
