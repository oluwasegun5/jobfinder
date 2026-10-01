package com.jobfinder.core.matching;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of running the three stages for a user: the top jobs by the stage-2 blend, re-ranked by the model.
 * {@code matches} are ordered best first: model-scored jobs by their model score, then the others by their stage-2
 * score. A feed (P3.3) reads from here.
 */
public record RankedMatches(UUID userId, UUID resumeVersionId, List<MatchResult> matches, Stats stats) {

    /**
     * @param recalled      jobs that passed the stage-1 filters and were recalled by similarity (at most the limit)
     * @param considered    jobs in the model re-rank window (the top of the stage-2 order)
     * @param cached        of those, how many already had a valid cached model score
     * @param llmScored     how many were scored by the model in this run
     * @param unranked      how many the model failed on
     * @param notScored     how many were left without a model score (cap, unavailable)
     * @param aiRequests    requests made to ai-service
     * @param capped        true if the user's daily allowance stopped the re-rank
     */
    public record Stats(int recalled, int considered, int cached, int llmScored, int unranked, int notScored,
            int aiRequests, boolean capped) {
    }
}
