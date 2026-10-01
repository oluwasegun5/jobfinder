package com.jobfinder.core.matching.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.matching.FallbackReason;
import com.jobfinder.core.matching.MatchResult;
import com.jobfinder.core.matching.MatchService;
import com.jobfinder.core.matching.MatchStatus;

/**
 * How well a job fits the caller (docs/adr/0026-matching-engine.md). The user is the token's user, never a parameter.
 */
@RestController
class MatchController {

    /**
     * @param score        0 to 100: the model's score when {@code status} is {@code LLM_SCORED}, otherwise the coarser
     *                     stage-2 score; show it as a model score only when {@code status} says so
     * @param stage2Score  0 to 100, the blend of embedding similarity, skill overlap and recency
     * @param llmScore     the model's score; absent unless {@code status} is {@code LLM_SCORED}
     * @param reason       why there is no model score; absent when there is one
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MatchResponse(UUID jobId, MatchStatus status, FallbackReason reason, int score, double stage2Score,
            Integer llmScore, List<String> strengths, List<String> gaps, String model, String promptVersion,
            Instant scoredAt) {

        static MatchResponse of(MatchResult r) {
            return new MatchResponse(r.jobId(), r.status(), r.reason(), r.score(), r.stage2Score(), r.llmScore(),
                    r.strengths(), r.gaps(), r.model(), r.promptVersion(), r.scoredAt());
        }
    }

    private final MatchService matches;

    MatchController(MatchService matches) {
        this.matches = matches;
    }

    /**
     * Scores the job for the caller's primary resume: a model score with strengths and gaps when the daily AI
     * allowance allows it (cached afterwards), otherwise the stage-2 score flagged {@code NOT_LLM_SCORED}. 409
     * {@code resume_required} without a primary resume that has been parsed; 404 for an unknown job.
     */
    @GetMapping("/jobs/{id}/match")
    MatchResponse match(@PathVariable UUID id) {
        return MatchResponse.of(matches.matchJob(CurrentUser.require().id(), id));
    }
}
