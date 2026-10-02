package com.jobfinder.core.documents.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.documents.internal.AiTailoringClient.AiUnavailableException;
import com.jobfinder.core.documents.internal.AiTailoringClient.InvalidContentException;
import com.jobfinder.core.documents.internal.AiWritingClient.ProfileFacts;
import com.jobfinder.core.documents.internal.AiWritingClient.TextItem;
import com.jobfinder.core.documents.internal.AiWritingClient.Written;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentStatus;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentType;
import com.jobfinder.core.documents.internal.DocumentDtos.FactCheck;
import com.jobfinder.core.documents.internal.DocumentDtos.Operation;
import com.jobfinder.core.documents.internal.DocumentDtos.OperationType;
import com.jobfinder.core.documents.internal.DocumentDtos.PatchRequest;
import com.jobfinder.core.documents.internal.DocumentService.Outcome;
import com.jobfinder.core.documents.internal.DocumentStore.Row;
import com.jobfinder.core.documents.internal.WritingDtos.WritingRequest;
import com.jobfinder.core.jobs.JobForMatching;
import com.jobfinder.core.jobs.JobMatchSource;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidatePreferences;
import com.jobfinder.core.profile.CandidateProfiles;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cover letters and screening answers (docs/adr/0031-cover-letters-and-application-pack.md): generate for a job from the
 * user's primary resume, edit, approve. It follows the rules of {@link DocumentService}: a GENERATING placeholder makes a
 * double click one model call, ai-service is called with no transaction open, and every edit re-runs the fact check
 * (an edit is refused when it cannot run, so a stored document never has stale flags).
 *
 * <p>The text of a letter or of an answer is the document, so there is no change list: an edit replaces one string of
 * the content ({@code salutation}, {@code closing}, {@code signature}, {@code paragraphs[n]}, {@code answers.<ID>}).
 * What the user types is held to the same rules as what the model wrote: a placeholder is refused, and prose that
 * claims an employer, a degree, a number or years of experience the resume does not show is a BLOCKING flag that stops
 * approval. Factual answers (salary, notice period, work authorization, location, years) are filled by code from the
 * profile; the model cannot change them, and one the profile cannot answer stays {@code NEEDS_INPUT} until the user
 * writes it, which is what keeps the set from being approved.
 *
 * <p>Regenerating with other options makes a new draft and marks the old one SUPERSEDED (history, read-only); an
 * APPROVED document is never touched, the new draft sits beside it.
 */
@Service
class WritingService {

    private static final Logger log = LoggerFactory.getLogger(WritingService.class);
    private static final Pattern PARAGRAPH = Pattern.compile("paragraphs\\[(\\d{1,2})]");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private final DocumentStore store;
    private final AiWritingClient ai;
    private final CandidateProfiles candidates;
    private final JobMatchSource jobs;
    private final AiUsageGate gate;
    private final DocumentsProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    WritingService(DocumentStore store, AiWritingClient ai, CandidateProfiles candidates, JobMatchSource jobs,
            AiUsageGate gate, DocumentsProperties properties, TransactionTemplate tx, JsonMapper json, Clock clock) {
        this.store = store;
        this.ai = ai;
        this.candidates = candidates;
        this.jobs = jobs;
        this.gate = gate;
        this.properties = properties;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    static boolean writes(DocumentType type) {
        return type == DocumentType.COVER_LETTER || type == DocumentType.SCREENING_ANSWERS;
    }

    // --- generate ---

    /**
     * Writes the letter or the answers for the job and returns the draft: created, or (not created) the open draft that
     * already has these options, or the one being made right now. A draft with other options is superseded by the new
     * one once that one is stored; if generating fails the old draft stays as it was.
     */
    Outcome generate(UUID userId, UUID jobId, DocumentType type, WritingRequest request) {
        if (!writes(type)) {
            throw new IllegalArgumentException("Not a written document: " + type);
        }
        Candidate candidate = candidates.candidate(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "resume_required", "Upload and parse a resume before writing for a job."));
        JobForMatching job = jobs.jobs(List.of(jobId)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "Job not found."));
        UUID versionId = candidate.resumeVersionId();

        store.deleteAbandoned(userId, jobId, versionId, type,
                Instant.now(clock).minus(properties.tailoring().generationTimeout()));
        Optional<Row> existing = store.findOpen(userId, jobId, versionId, type, false);
        if (existing.isPresent() && (existing.get().status() == DocumentStatus.GENERATING
                || sameOptions(existing.get(), request))) {
            return new Outcome(existing.get(), false);
        }
        // Only a call that will really be made is checked against (and later billed to) the daily cap.
        String feature = type == DocumentType.COVER_LETTER ? AiWritingClient.LETTER_FEATURE
                : AiWritingClient.ANSWERS_FEATURE;
        gate.requireAllowance(userId, feature);

        JsonNode source = readTree(candidate.structuredJson());
        String promptVersion = type == DocumentType.COVER_LETTER ? properties.letterPromptVersion()
                : properties.answersPromptVersion();
        UUID id = UUID.randomUUID();
        try {
            store.insertPlaceholder(id, userId, type, jobId, clip(job.title(), 400), clip(job.company(), 400),
                    versionId, promptVersion, source, options(request));
        } catch (DuplicateKeyException e) {
            // Another request got there first: hand back what is there.
            Row row = store.findOpen(userId, jobId, versionId, type, false).orElseThrow(
                    () -> new ApiException(HttpStatus.CONFLICT, "generation_in_progress",
                            "This document is being made. Try again in a moment."));
            return new Outcome(row, false);
        }

        Written written;
        try {
            written = write(userId, type, promptVersion, source, job, candidate, request);
        } catch (AiUnavailableException e) {
            store.deleteStale(id);
            log.warn("Writing {} failed for a job: {}", type, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "writing_unavailable",
                    "Writing is unavailable right now. Try again in a moment.");
        } catch (RuntimeException e) {
            store.deleteStale(id);
            throw e;
        }
        DocumentStatus status = written.factCheck().blocking() > 0 ? DocumentStatus.FACT_CHECK_FAILED
                : DocumentStatus.DRAFT;
        tx.executeWithoutResult(s -> {
            store.supersedeDrafts(userId, jobId, versionId, type);
            if (!store.complete(id, status, written.model(), written.promptVersion(), written.content(), List.of(),
                    written.factCheck())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "document_not_found", "Document not found.");
            }
        });
        return new Outcome(store.find(userId, id, false).orElseThrow(), true);
    }

    private Written write(UUID userId, DocumentType type, String promptVersion, JsonNode source, JobForMatching job,
            Candidate candidate, WritingRequest request) {
        String title = clip(job.title(), 400);
        String company = clip(job.company(), 400);
        String description = clip(job.descriptionText(), properties.tailoring().descriptionChars());
        LocalDate today = LocalDate.now(clock);
        if (type == DocumentType.COVER_LETTER) {
            return ai.coverLetter(userId, promptVersion, source, title, company, description, request.tone(),
                    request.length(), request.notes(), candidate.yearsExperience(), today);
        }
        CandidatePreferences p = candidate.preferences() == null ? CandidatePreferences.EMPTY
                : candidate.preferences();
        ProfileFacts facts = new ProfileFacts(candidate.yearsExperience(), nonNull(p.locations()),
                nonNull(p.workModes()), p.minSalary(), p.currency(), p.needsSponsorship());
        return ai.screeningAnswers(userId, promptVersion, source, title, company, description,
                nonNull(job.skills()), facts, request.tone(), request.length(), request.notes(), today);
    }

    private ObjectNode options(WritingRequest request) {
        ObjectNode options = json.createObjectNode();
        options.put("tone", request.tone().name());
        options.put("length", request.length().name());
        if (request.notes() != null) {
            options.put("notes", request.notes());
        }
        return options;
    }

    private static boolean sameOptions(Row row, WritingRequest request) {
        JsonNode o = row.options();
        return o != null && request.tone().name().equals(o.path("tone").asString(null))
                && request.length().name().equals(o.path("length").asString(null))
                && Objects.equals(request.notes(), o.path("notes").asString(null));
    }

    // --- review ---

    Row patch(UUID userId, UUID id, PatchRequest request) {
        tx.executeWithoutResult(status -> {
            Row row = store.find(userId, id, true).orElseThrow(WritingService::notFound);
            requireEditable(row);
            if (row.version() != request.version()) {
                throw new ApiException(HttpStatus.CONFLICT, "version_conflict",
                        "The document changed since you loaded it (now version " + row.version() + ").")
                        .withProperty("currentVersion", row.version());
            }
            ObjectNode content = (ObjectNode) row.content().deepCopy();
            for (Operation op : request.operations()) {
                if (op.op() != OperationType.EDIT || op.path() == null || op.changeId() != null
                        || op.after() == null) {
                    throw invalid("A " + label(row.type()) + " is edited with EDIT operations that have a path and the"
                            + " new text in 'after'.");
                }
                if (row.type() == DocumentType.COVER_LETTER) {
                    editLetter(content, op.path(), text(op.after()));
                } else {
                    editAnswer(content, op.path(), text(op.after()));
                }
            }
            FactCheck check = factCheck(userId, row, content);
            store.save(id, check.blocking() > 0 ? DocumentStatus.FACT_CHECK_FAILED : DocumentStatus.DRAFT, content,
                    List.of(), check);
        });
        return store.find(userId, id, false).orElseThrow(WritingService::notFound);
    }

    Row approve(UUID userId, UUID id) {
        Integer blocked = tx.execute(status -> {
            Row row = store.find(userId, id, true).orElseThrow(WritingService::notFound);
            if (row.status() == DocumentStatus.APPROVED) {
                throw new ApiException(HttpStatus.CONFLICT, "already_approved", "This document is already approved.");
            }
            requireEditable(row);
            if (row.status() == DocumentStatus.FACT_CHECK_FAILED) {
                return row.factCheck().blocking();
            }
            List<String> open = unanswered(row);
            if (!open.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "answers_incomplete", open.size() + " question"
                        + (open.size() == 1 ? " has" : "s have") + " no answer yet. Write them, then approve again.")
                        .withProperty("needsInput", open);
            }
            // The flags are recomputed from the content that is about to become final, not trusted from earlier.
            FactCheck check = factCheck(userId, row, row.content());
            if (check.blocking() > 0) {
                store.save(id, DocumentStatus.FACT_CHECK_FAILED, row.content(), List.of(), check);
                return check.blocking();
            }
            store.approve(id, check);
            return 0;
        });
        if (blocked != null && blocked > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "fact_check_failed",
                    "This document has " + blocked + " blocking fact-check flag" + (blocked == 1 ? "" : "s")
                            + ". Edit the text that introduces them, then approve again.")
                    .withProperty("blocking", blocked);
        }
        return store.find(userId, id, false).orElseThrow(WritingService::notFound);
    }

    private static void requireEditable(Row row) {
        switch (row.status()) {
            case APPROVED -> throw new ApiException(HttpStatus.CONFLICT, "document_approved",
                    "An approved document cannot be changed.");
            case SUPERSEDED -> throw new ApiException(HttpStatus.CONFLICT, "document_superseded",
                    "This draft was replaced by a newer one and is kept as history only.");
            case GENERATING -> throw new ApiException(HttpStatus.CONFLICT, "document_generating",
                    "The document is still being made. Try again in a moment.");
            default -> {
            }
        }
    }

    /** The ids of the questions of an answer set that have no answer. Empty for a letter. */
    private static List<String> unanswered(Row row) {
        List<String> open = new ArrayList<>();
        if (row.type() == DocumentType.SCREENING_ANSWERS) {
            for (JsonNode a : row.content().path("answers")) {
                if ("NEEDS_INPUT".equals(a.path("status").asString(null))) {
                    open.add(a.path("id").asString());
                }
            }
        }
        return open;
    }

    // --- edits ---

    private void editLetter(ObjectNode content, String path, String text) {
        switch (path) {
            case "salutation" -> content.put("salutation", checked(text, 120));
            case "closing" -> content.put("closing", checked(text, 60));
            case "signature" -> content.put("signature", checked(text, 200));
            default -> {
                Matcher m = PARAGRAPH.matcher(path);
                ArrayNode paragraphs = (ArrayNode) content.get("paragraphs");
                if (!m.matches() || Integer.parseInt(m.group(1)) >= paragraphs.size()) {
                    throw invalid("The path must be salutation, closing, signature or paragraphs[n] of an existing"
                            + " paragraph.");
                }
                paragraphs.set(Integer.parseInt(m.group(1)), json.valueToTree(checked(text, 1600)));
            }
        }
    }

    private void editAnswer(ObjectNode content, String path, String text) {
        if (!path.startsWith("answers.")) {
            throw invalid("The path must be answers.<question id>, such as answers.NOTICE_PERIOD.");
        }
        String id = path.substring("answers.".length());
        for (JsonNode node : content.path("answers")) {
            if (id.equals(node.path("id").asString(null))) {
                ObjectNode answer = (ObjectNode) node;
                answer.put("answer", checked(text, 1200));
                // The model's own answers stay GENERATED (and stay fact-checked); anything else is now the user's.
                if (!"GENERATED".equals(answer.path("status").asString(null))) {
                    answer.put("status", "USER_PROVIDED");
                    answer.remove("hint");
                }
                return;
            }
        }
        throw invalid("There is no question " + id + ".");
    }

    private static String text(JsonNode after) {
        if (!after.isString()) {
            throw invalid("'after' must be a string.");
        }
        return after.asString().strip();
    }

    /** The text of an edit: not blank, within its limit, one line, and never a placeholder. */
    private static String checked(String text, int max) {
        if (text.isEmpty() || text.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_content",
                    "The text must have between 1 and " + max + " characters.");
        }
        if (CONTROL.matcher(text).find()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_content",
                    "The text must be plain text on one line.");
        }
        if (AiWritingClient.PLACEHOLDER.matcher(text).find()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_content",
                    "The text still contains a placeholder such as [Your Name] or NEEDS_INPUT. Write the real text.");
        }
        return text;
    }

    // --- fact check ---

    /**
     * The deterministic fact check of the prose against the resume it was written from. Of an answer set only the
     * model's own answers are checked: the factual ones come from the profile and the rest were typed by the user.
     */
    FactCheck factCheck(UUID userId, Row row, JsonNode content) {
        List<TextItem> items = new ArrayList<>();
        if (row.type() == DocumentType.COVER_LETTER) {
            items.add(new TextItem("salutation", content.path("salutation").asString("")));
            int i = 0;
            for (JsonNode p : content.path("paragraphs")) {
                items.add(new TextItem("paragraphs[" + i++ + "]", p.asString("")));
            }
            items.add(new TextItem("closing", content.path("closing").asString("")));
            if (content.path("signature").isString()) {
                items.add(new TextItem("signature", content.path("signature").asString()));
            }
        } else {
            for (JsonNode a : content.path("answers")) {
                if ("GENERATED".equals(a.path("status").asString(null))) {
                    items.add(new TextItem("answers." + a.path("id").asString(), a.path("answer").asString("")));
                }
            }
        }
        if (items.isEmpty()) {
            String version = row.factCheck() == null ? "none" : row.factCheck().checkerVersion();
            return new FactCheck(true, 0, 0, List.of(), version);
        }
        Integer years = candidates.candidate(userId).map(Candidate::yearsExperience).orElse(null);
        try {
            return ai.factCheckText(row.source(), items, jobDescription(row.jobId()), allowedContext(row), years,
                    LocalDate.now(clock));
        } catch (InvalidContentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_content",
                    "The text is not valid. Check its lengths.");
        } catch (AiUnavailableException e) {
            log.warn("Text fact check unavailable: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "fact_check_unavailable",
                    "The fact check is unavailable right now, so nothing was changed. Try again in a moment.");
        }
    }

    /** What the prose may name without the resume showing it: the job's own title and company, and the user's notes. */
    private static String allowedContext(Row row) {
        List<String> parts = new ArrayList<>();
        parts.add(row.jobTitle());
        if (row.jobCompany() != null) {
            parts.add(row.jobCompany());
        }
        if (row.options() != null && row.options().path("notes").isString()) {
            parts.add(row.options().get("notes").asString());
        }
        return String.join("\n", parts);
    }

    private String jobDescription(UUID jobId) {
        return jobs.jobs(List.of(jobId)).stream().findFirst()
                .map(j -> clip(j.descriptionText(), properties.tailoring().descriptionChars())).orElse(null);
    }

    // --- plumbing ---

    private static String label(DocumentType type) {
        return type == DocumentType.COVER_LETTER ? "cover letter" : "set of answers";
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_operation", detail);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "document_not_found", "Document not found.");
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored resume content is unreadable", e);
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
