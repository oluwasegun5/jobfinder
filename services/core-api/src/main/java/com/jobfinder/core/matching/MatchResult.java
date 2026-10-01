package com.jobfinder.core.matching;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One job scored for one user.
 *
 * @param score        0 to 100: the model's score when {@code status} is {@code LLM_SCORED}, else the stage-2
 *                     (recall) score rounded, which is a cheaper and coarser estimate; always check {@code status}
 * @param stage2Score  0 to 100: the blend of embedding similarity, skill overlap and recency
 * @param llmScore     the model's score, or null when {@code status} is not {@code LLM_SCORED}
 * @param reason       why there is no model score; null when there is one
 * @param model        the model that scored it; null when there is no model score
 * @param scoredAt     when the model scored it; null when there is no model score
 */
public record MatchResult(UUID jobId, UUID resumeVersionId, MatchStatus status, FallbackReason reason, int score,
        double stage2Score, Integer llmScore, List<String> strengths, List<String> gaps, String model,
        String promptVersion, Instant scoredAt) {
}
