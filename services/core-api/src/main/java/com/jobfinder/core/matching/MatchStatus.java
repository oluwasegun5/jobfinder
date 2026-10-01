package com.jobfinder.core.matching;

/** How a match's score came about. */
public enum MatchStatus {
    /** Scored by the language model (now, or earlier and cached): {@link MatchResult#score()} is its score. */
    LLM_SCORED,
    /** The model was asked and failed for this job (invalid output, refusal): the stage-2 score stands in. */
    UNRANKED,
    /** The model was not asked (daily cap reached, ai-service unavailable, expired job): the stage-2 score only. */
    NOT_LLM_SCORED
}
