package com.jobfinder.core.interview.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.interview.InterviewPrep;
import com.jobfinder.core.interview.internal.AiInterviewClient.AiUnavailableException;
import com.jobfinder.core.interview.internal.AiInterviewClient.Prep;
import com.jobfinder.core.interview.internal.InterviewStore.Row;
import com.jobfinder.core.jobs.JobBriefSource;
import com.jobfinder.core.jobs.JobForBrief;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidateProfiles;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Interview prep (docs/adr/0033-interview-prep.md). A prep is made once per user, job and prompt version: the unique
 * key of {@code interview_prep} is the idempotency key, so a repeated request returns what is stored, calls no model
 * and is not checked against (or charged to) the daily cap. A GENERATING placeholder is inserted before ai-service is
 * called, which makes a double click, or two tabs, one model call (the second request is answered with the placeholder,
 * status GENERATING, to be read again with {@code GET}); ai-service is called with no transaction open; a failed
 * generation deletes its placeholder so the next try starts clean.
 *
 * <p>The model is told the user's primary resume (for the questions) and the job and company record (for both); it is
 * never told anything else. The cap is checked only for a call that will really be made, and every billed call is
 * recorded in the ledger by {@link AiInterviewClient}, stored or discarded.
 */
@Service
class InterviewService implements InterviewPrep {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private final InterviewStore store;
    private final AiInterviewClient ai;
    private final CandidateProfiles candidates;
    private final JobBriefSource jobs;
    private final AiUsageGate gate;
    private final InterviewProperties properties;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    InterviewService(InterviewStore store, AiInterviewClient ai, CandidateProfiles candidates, JobBriefSource jobs,
            AiUsageGate gate, InterviewProperties properties, TransactionTemplate tx, JsonMapper json, Clock clock) {
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

    @Override
    public Generated generate(UUID userId, UUID jobId) {
        JobForBrief job = jobs.job(jobId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "Job not found."));
        String promptVersion = properties.promptVersion();

        store.deleteAbandoned(userId, jobId, promptVersion, Instant.now(clock).minus(properties.generationTimeout()));
        var existing = store.findByKey(userId, jobId, promptVersion);
        if (existing.isPresent()) {
            return existing(existing.get());
        }
        Candidate candidate = candidates.candidate(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "resume_required", "Upload and parse a resume before preparing for an interview."));
        // Only a call that will really be made is checked against (and later billed to) the daily cap.
        gate.requireAllowance(userId, AiInterviewClient.FEATURE);

        UUID id = UUID.randomUUID();
        try {
            store.insertPlaceholder(id, userId, jobId, clip(job.title(), 400), clip(job.company(), 400),
                    promptVersion);
        } catch (DuplicateKeyException e) {
            // Another request got there first: hand back what is there.
            return existing(store.findByKey(userId, jobId, promptVersion).orElseThrow(
                    () -> new ApiException(HttpStatus.CONFLICT, "generation_in_progress", inProgress())));
        }

        Prep prep;
        try {
            prep = ai.generate(userId, promptVersion, readTree(candidate.structuredJson()), job);
        } catch (AiUnavailableException e) {
            store.deletePlaceholder(id);
            log.warn("Interview prep failed for a job: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interview_prep_unavailable",
                    "Interview prep is unavailable right now. Try again in a moment.");
        } catch (RuntimeException e) {
            store.deletePlaceholder(id);
            throw e;
        }
        Boolean stored = tx.execute(status -> store.complete(id, prep.questionsModel(), prep.briefModel(),
                prep.questionsDropped(), prep.questions(), prep.sections(), prep.unknowns(), prep.droppedClaims()));
        if (!Boolean.TRUE.equals(stored)) {
            // The placeholder was replaced as abandoned while the model worked; the other request owns the result.
            throw new ApiException(HttpStatus.CONFLICT, "generation_in_progress", inProgress());
        }
        return new Generated(view(store.find(userId, id).orElseThrow()), true);
    }

    @Override
    public InterviewPrepView get(UUID userId, UUID prepId) {
        return view(store.find(userId, prepId).orElseThrow(InterviewService::notFound));
    }

    /** What is stored: the prep, or the placeholder of the request that is making it right now (status GENERATING). */
    private Generated existing(Row row) {
        return new Generated(view(row), false);
    }

    private InterviewPrepView view(Row row) {
        if (!row.ready()) {
            return new InterviewPrepView(row.id(), row.jobId(), row.jobTitle(), row.jobCompany(), row.status(),
                    row.promptVersion(), row.model(), row.createdAt(), List.of(), null);
        }
        return new InterviewPrepView(row.id(), row.jobId(), row.jobTitle(), row.jobCompany(), row.status(),
                row.promptVersion(), row.model(), row.createdAt(), store.questions(row.id()),
                store.brief(row.id()).orElse(null));
    }

    private static String inProgress() {
        return "This interview prep is being made. Try again in a moment.";
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "interview_prep_not_found", "Interview prep not found.");
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
