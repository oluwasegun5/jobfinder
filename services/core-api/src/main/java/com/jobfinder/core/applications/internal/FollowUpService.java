package com.jobfinder.core.applications.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jobfinder.core.applications.internal.AiFollowUpClient.AiUnavailableException;
import com.jobfinder.core.applications.internal.AiFollowUpClient.Draft;
import com.jobfinder.core.applications.internal.AiFollowUpClient.Facts;
import com.jobfinder.core.applications.internal.AiFollowUpClient.RejectedDraftException;
import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationStatus;
import com.jobfinder.core.applications.internal.ApplicationDtos.FollowUpDraft;
import com.jobfinder.core.applications.internal.ApplicationDtos.FollowUpRequest;
import com.jobfinder.core.applications.internal.ApplicationStore.Row;
import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.jobs.JobMatchSource;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidateProfiles;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AI follow-up email draft (docs/adr/0032-application-tracker.md). It makes a draft and returns it: nothing is stored
 * and nothing is ever sent, the user reads, edits and sends it themselves.
 *
 * <p>The model is told only what the tracker knows (the title, company, status and how long ago the user applied; the job
 * description when the application came from a job we still have; the notes the user typed for this draft) and the
 * user's primary resume. The notes kept on the application are not sent: they are the user's private record. The
 * request is metered like the writing endpoints: the daily allowance is checked before the call (429
 * {@code ai_daily_cap_reached}), every billed call is recorded in the ledger whether the draft is returned or discarded,
 * and ai-service being down, answering badly or producing a draft the fact check blocks is a typed 503 with nothing
 * left behind.
 */
@Service
class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);

    private final ApplicationStore store;
    private final AiFollowUpClient ai;
    private final CandidateProfiles candidates;
    private final JobMatchSource jobs;
    private final AiUsageGate gate;
    private final ApplicationsProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    FollowUpService(ApplicationStore store, AiFollowUpClient ai, CandidateProfiles candidates, JobMatchSource jobs,
            AiUsageGate gate, ApplicationsProperties properties, JsonMapper json, Clock clock) {
        this.store = store;
        this.ai = ai;
        this.candidates = candidates;
        this.jobs = jobs;
        this.gate = gate;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    FollowUpDraft draft(UUID userId, UUID applicationId, FollowUpRequest request) {
        Row app = store.find(userId, applicationId).orElseThrow(ApplicationService::notFound);
        if (app.status() == ApplicationStatus.SAVED) {
            throw new ApiException(HttpStatus.CONFLICT, "not_applied_yet",
                    "A follow-up email is for an application that was made. Move it out of SAVED first.");
        }
        Candidate candidate = candidates.candidate(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "resume_required", "Upload and parse a resume before writing for an application."));
        // Only a call that will really be made is checked against (and later billed to) the daily cap.
        gate.requireAllowance(userId, AiFollowUpClient.FEATURE);

        String description = app.jobId() == null ? null : jobs.jobs(List.of(app.jobId())).stream().findFirst()
                .map(j -> clip(j.descriptionText(), properties.followUp().descriptionChars())).orElse(null);
        Facts facts = new Facts(app.title(), app.company(), app.status().name(),
                app.appliedAt() == null ? null : app.appliedAt().atZone(ZoneOffset.UTC).toLocalDate());
        try {
            Draft draft = ai.draft(userId, properties.followUp().promptVersion(), readTree(candidate.structuredJson()),
                    facts, description, request.tone(), request.length(), request.notes(),
                    candidate.yearsExperience(), LocalDate.now(clock));
            return new FollowUpDraft(draft.subject(), draft.body(), request.tone(), request.length(), draft.model(),
                    draft.promptVersion());
        } catch (AiUnavailableException e) {
            log.warn("Follow-up draft failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "follow_up_unavailable",
                    "Writing is unavailable right now. Try again in a moment.");
        } catch (RejectedDraftException e) {
            log.warn("Follow-up draft discarded: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "follow_up_rejected",
                    "The draft claimed something your resume does not show, so it was discarded. Try again.");
        }
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
