package com.jobfinder.core.documents.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.documents.internal.AiTailoringClient.AiUnavailableException;
import com.jobfinder.core.documents.internal.AiTailoringClient.InvalidContentException;
import com.jobfinder.core.documents.internal.AiTailoringClient.Tailored;
import com.jobfinder.core.documents.internal.ChangeMaterializer.Result;
import com.jobfinder.core.documents.internal.DocumentDtos.Change;
import com.jobfinder.core.documents.internal.DocumentDtos.ChangeState;
import com.jobfinder.core.documents.internal.DocumentDtos.ChangeView;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentStatus;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentType;
import com.jobfinder.core.documents.internal.DocumentDtos.DraftResponse;
import com.jobfinder.core.documents.internal.DocumentDtos.FactCheck;
import com.jobfinder.core.documents.internal.DocumentDtos.FactCheckView;
import com.jobfinder.core.documents.internal.DocumentDtos.FlagView;
import com.jobfinder.core.documents.internal.DocumentDtos.JobRef;
import com.jobfinder.core.documents.internal.DocumentDtos.ListResponse;
import com.jobfinder.core.documents.internal.DocumentDtos.Operation;
import com.jobfinder.core.documents.internal.DocumentDtos.PatchRequest;
import com.jobfinder.core.documents.internal.DocumentDtos.SummaryResponse;
import com.jobfinder.core.documents.internal.DocumentDtos.TailorOptions;
import com.jobfinder.core.documents.internal.DocumentStore.Row;
import com.jobfinder.core.jobs.JobForMatching;
import com.jobfinder.core.jobs.JobMatchSource;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidateProfiles;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resume tailoring drafts (docs/adr/0029-resume-tailoring.md): create from the user's primary resume and a job, review
 * change by change, approve. Every method takes the caller's user id and finds documents only through it, so another
 * user's document is a 404.
 *
 * <p>Creation is synchronous with a placeholder: a GENERATING row is inserted first (the unique index on open drafts
 * makes a second click or tab get that row back instead of a second model call), ai-service is called with no
 * transaction open, and the row is filled in. An ai-service failure removes the placeholder; a request that dies
 * leaves a GENERATING row that is replaced once it is older than {@code generation-timeout}.
 *
 * <p>The content of a draft is always {@link ChangeMaterializer#materialize} of its source and its changes, and its fact
 * check is always the check of that content: every edit re-runs the check in ai-service before anything is stored
 * (if ai-service is down the edit is refused, so a stored draft never has stale flags), and approval runs it once more.
 */
@Service
class DocumentService {

    /** A draft and whether this call created it (false: an open draft already existed and was returned). */
    record Outcome(Row row, boolean created) {
    }

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final DocumentStore store;
    private final AiTailoringClient ai;
    private final CandidateProfiles candidates;
    private final JobMatchSource jobs;
    private final AiUsageGate gate;
    private final DocumentsProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final WritingService writing;

    DocumentService(DocumentStore store, AiTailoringClient ai, CandidateProfiles candidates, JobMatchSource jobs,
            AiUsageGate gate, DocumentsProperties properties, TransactionTemplate tx, JsonMapper json, Clock clock,
            WritingService writing) {
        this.writing = writing;
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

    // --- create ---

    Outcome tailor(UUID userId, UUID jobId, TailorOptions options) {
        Candidate candidate = candidates.candidate(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "resume_required", "Upload and parse a resume before tailoring it to a job."));
        JobForMatching job = jobs.jobs(List.of(jobId)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "Job not found."));

        Optional<Row> existing = store.findOpen(userId, jobId, candidate.resumeVersionId(), DocumentType.TAILORED_RESUME,
                false);
        if (existing.isPresent()) {
            Row row = existing.get();
            if (!abandoned(row)) {
                return new Outcome(row, false);
            }
            store.deleteStale(row.id());
        }
        // Only a call that will really be made is checked against (and later billed to) the daily cap.
        gate.requireAllowance(userId, AiTailoringClient.FEATURE);

        JsonNode source = readTree(candidate.structuredJson());
        UUID id = UUID.randomUUID();
        try {
            store.insertPlaceholder(id, userId, DocumentType.TAILORED_RESUME, jobId, clip(job.title(), 400),
                    clip(job.company(), 400), candidate.resumeVersionId(), properties.promptVersion(), source, null);
        } catch (DuplicateKeyException e) {
            // Another request got there first: hand back its draft.
            Row row = store.findOpen(userId, jobId, candidate.resumeVersionId(), DocumentType.TAILORED_RESUME, false)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "tailoring_in_progress",
                            "A draft for this job is being made. Try again in a moment."));
            return new Outcome(row, false);
        }

        Tailored tailored;
        try {
            tailored = ai.tailor(userId, properties.promptVersion(), source, clip(job.title(), 400), clip(job.company(), 400),
                    clip(job.descriptionText(), properties.tailoring().descriptionChars()), options);
        } catch (AiUnavailableException e) {
            store.deleteStale(id);
            log.warn("Tailoring failed for a job: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tailoring_unavailable",
                    "Resume tailoring is unavailable right now. Try again in a moment.");
        } catch (RuntimeException e) {
            store.deleteStale(id);
            throw e;
        }
        Result result = ChangeMaterializer.materialize(source, tailored.changes());
        DocumentStatus status = tailored.factCheck().blocking() > 0 ? DocumentStatus.FACT_CHECK_FAILED
                : DocumentStatus.DRAFT;
        boolean stored = store.complete(id, status, tailored.model(), tailored.promptVersion(), result.content(),
                tailored.changes(), tailored.factCheck());
        if (!stored) {
            throw new ApiException(HttpStatus.NOT_FOUND, "document_not_found", "Document not found.");
        }
        return new Outcome(store.find(userId, id, false).orElseThrow(), true);
    }

    private boolean abandoned(Row row) {
        return row.status() == DocumentStatus.GENERATING
                && row.updatedAt().plus(properties.tailoring().generationTimeout()).isBefore(Instant.now(clock));
    }

    // --- read ---

    Row get(UUID userId, UUID id) {
        return store.find(userId, id, false).orElseThrow(DocumentService::notFound);
    }

    List<Row> list(UUID userId, UUID jobId, DocumentType type, DocumentStatus status, Integer limit) {
        return store.list(userId, jobId, type, status, limit == null ? 20 : limit);
    }

    // --- review ---

    Row patch(UUID userId, UUID id, PatchRequest request) {
        if (get(userId, id).type() != DocumentType.TAILORED_RESUME) {
            return writing.patch(userId, id, request);
        }
        tx.executeWithoutResult(status -> {
            Row row = store.find(userId, id, true).orElseThrow(DocumentService::notFound);
            requireOpen(row);
            if (row.version() != request.version()) {
                throw new ApiException(HttpStatus.CONFLICT, "version_conflict",
                        "The draft changed since you loaded it (now version " + row.version() + ").")
                        .withProperty("currentVersion", row.version());
            }
            List<Change> changes = apply(row.source(), row.changes(), request.operations());
            Result result = ChangeMaterializer.materialize(row.source(), changes);
            FactCheck check = factCheck(row, result.content());
            store.save(id, check.blocking() > 0 ? DocumentStatus.FACT_CHECK_FAILED : DocumentStatus.DRAFT,
                    result.content(), changes, check);
        });
        return get(userId, id);
    }

    Row approve(UUID userId, UUID id) {
        if (get(userId, id).type() != DocumentType.TAILORED_RESUME) {
            return writing.approve(userId, id);
        }
        Integer blocked = tx.execute(status -> {
            Row row = store.find(userId, id, true).orElseThrow(DocumentService::notFound);
            if (row.status() == DocumentStatus.APPROVED) {
                throw new ApiException(HttpStatus.CONFLICT, "already_approved", "This document is already approved.");
            }
            requireOpen(row);
            if (row.status() == DocumentStatus.FACT_CHECK_FAILED) {
                return row.factCheck().blocking();
            }
            // The flags are recomputed from the content that is about to become final, not trusted from earlier.
            FactCheck check = factCheck(row, row.content());
            if (check.blocking() > 0) {
                store.save(id, DocumentStatus.FACT_CHECK_FAILED, row.content(), row.changes(), check);
                return check.blocking();
            }
            store.approve(id, check);
            return 0;
        });
        if (blocked != null && blocked > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "fact_check_failed",
                    "This draft has " + blocked + " blocking fact-check flag" + (blocked == 1 ? "" : "s")
                            + ". Reject or edit the changes that introduce them, then approve again.")
                    .withProperty("blocking", blocked);
        }
        return get(userId, id);
    }

    void delete(UUID userId, UUID id) {
        Row row = store.find(userId, id, false).orElseThrow(DocumentService::notFound);
        if (row.status() == DocumentStatus.APPROVED) {
            throw approvedError();
        }
        if (!store.deleteDraft(userId, id)) {
            throw notFound();
        }
    }

    private static void requireOpen(Row row) {
        if (row.status() == DocumentStatus.APPROVED) {
            throw approvedError();
        }
        if (row.status() == DocumentStatus.SUPERSEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "document_superseded",
                    "This draft was replaced by a newer one and is kept as history only.");
        }
        if (row.status() == DocumentStatus.GENERATING) {
            throw new ApiException(HttpStatus.CONFLICT, "document_generating",
                    "The draft is still being made. Try again in a moment.");
        }
    }

    private static ApiException approvedError() {
        return new ApiException(HttpStatus.CONFLICT, "document_approved", "An approved document cannot be changed.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "document_not_found", "Document not found.");
    }

    private FactCheck factCheck(Row row, JsonNode content) {
        try {
            return ai.factCheck(row.source(), content, jobDescription(row.jobId()));
        } catch (InvalidContentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_content",
                    "The resume content is not valid. Check the dates and the lengths of the texts.");
        } catch (AiUnavailableException e) {
            log.warn("Fact check unavailable: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "fact_check_unavailable",
                    "The fact check is unavailable right now, so nothing was changed. Try again in a moment.");
        }
    }

    /** The job text the draft was written for, if the job still exists (it lets the check spot copied text). */
    private String jobDescription(UUID jobId) {
        return jobs.jobs(List.of(jobId)).stream().findFirst()
                .map(j -> clip(j.descriptionText(), properties.tailoring().descriptionChars())).orElse(null);
    }

    // --- operations ---

    private List<Change> apply(JsonNode source, List<Change> current, List<Operation> operations) {
        List<Change> changes = new ArrayList<>(current);
        for (Operation op : operations) {
            switch (op.op()) {
                case SET_STATE -> {
                    if (op.changeId() == null || op.state() == null) {
                        throw invalid("SET_STATE needs changeId and state.");
                    }
                    int at = indexOf(changes, op.changeId());
                    changes.set(at, changes.get(at).withState(op.state()));
                }
                case EDIT -> edit(source, changes, op);
                default -> throw invalid("Unknown operation.");
            }
        }
        return changes;
    }

    private void edit(JsonNode source, List<Change> changes, Operation op) {
        if ((op.changeId() == null) == (op.path() == null)) {
            throw invalid("EDIT needs exactly one of changeId and path.");
        }
        if (op.after() == null) {
            throw invalid("EDIT needs the new content in 'after'.");
        }
        int at = op.changeId() != null ? indexOf(changes, op.changeId()) : indexOfPath(changes, op.path());
        if (at >= 0) {
            Change change = changes.get(at);
            ChangeMaterializer.validateAfter(change.section(), op.after());
            String kind = "REMOVE".equals(change.op()) ? "REPLACE" : change.op();
            changes.set(at, new Change(change.id(), change.section(), kind, change.path(), change.before(),
                    op.after(), change.rationale(), ChangeState.ACCEPTED, true));
            return;
        }
        String section = ChangeMaterializer.sectionOf(op.path());
        if (section == null) {
            throw invalid("The path must be headline, summary, skills or an entry such as experience[0].");
        }
        JsonNode before = ChangeMaterializer.unitOf(source, op.path());
        ChangeMaterializer.validateAfter(section, op.after());
        changes.add(new Change(nextId(changes), section, "REPLACE", op.path(), before, op.after(),
                "Edited by you.", ChangeState.ACCEPTED, true));
    }

    private static int indexOf(List<Change> changes, String id) {
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "change_not_found", "There is no change " + id + ".");
    }

    /** The REPLACE or REMOVE change that addresses this source unit, or -1 (ADD changes are addressed by id). */
    private static int indexOfPath(List<Change> changes, String path) {
        for (int i = 0; i < changes.size(); i++) {
            Change c = changes.get(i);
            if (!"ADD".equals(c.op()) && c.path().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    private static String nextId(List<Change> changes) {
        int max = 0;
        for (Change c : changes) {
            try {
                max = Math.max(max, Integer.parseInt(c.id().substring(1)));
            } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                // an id that is not c<number> does not take part in numbering
            }
        }
        return "c" + (max + 1);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_operation", detail);
    }

    // --- views ---

    DraftResponse toResponse(Row row) {
        Map<String, Object> content = row.content() == null ? null : json.convertValue(row.content(), MAP);
        Result result = row.content() == null || row.type() != DocumentType.TAILORED_RESUME ? null
                : ChangeMaterializer.materialize(row.source(), row.changes());
        List<ChangeView> changes = row.changes().stream()
                .map(c -> new ChangeView(c.id(), c.section(), c.op(), c.path(), plain(c.before()), plain(c.after()),
                        c.rationale(), c.state(), c.edited()))
                .toList();
        FactCheckView factCheck = row.factCheck() == null ? null : new FactCheckView(row.factCheck().passed(),
                row.factCheck().blocking(), row.factCheck().warnings(),
                row.factCheck().flags().stream().map(f -> new FlagView(f.code(), f.severity(), f.path(), f.value(),
                        f.message(), result == null ? null : result.changeFor(f.path()))).toList(),
                row.factCheck().checkerVersion());
        return new DraftResponse(row.id(), row.type(), row.status(),
                new JobRef(row.jobId(), row.jobTitle(), row.jobCompany()), row.baseResumeVersionId(),
                row.promptVersion(), row.model(), row.version(), content, changes, factCheck,
                row.options() == null ? null : json.convertValue(row.options(), MAP), row.createdAt(), row.updatedAt(),
                row.approvedAt());
    }

    ListResponse toList(List<Row> rows) {
        return new ListResponse(rows.stream().map(r -> new SummaryResponse(r.id(), r.type(), r.status(),
                new JobRef(r.jobId(), r.jobTitle(), r.jobCompany()), r.version(),
                r.factCheck() == null ? null : r.factCheck().blocking(),
                r.factCheck() == null ? null : r.factCheck().warnings(), r.createdAt(), r.updatedAt(),
                r.approvedAt())).toList());
    }

    private Object plain(JsonNode node) {
        return node == null ? null : json.convertValue(node, Object.class);
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
