package com.jobfinder.core.matching;

import java.util.UUID;

/**
 * Matching a user's primary resume against jobs (docs/adr/0026-matching-engine.md). Both methods read and fill the
 * score cache and record any AI usage against the user, and neither fails when the user's daily AI allowance is used
 * up: the result then carries the stage-2 score flagged {@link MatchStatus#NOT_LLM_SCORED}.
 */
public interface MatchService {

    /**
     * Scores one job for the user, for when they open it. The job is scored whatever the user's filters say (they
     * opened it); a cached score for the same resume version, job content and prompt makes no model call.
     *
     * @throws com.jobfinder.core.shared.ApiException 404 {@code job_not_found}; 409 {@code resume_required} when the
     *         user has no primary resume with parsed content
     */
    MatchResult matchJob(UUID userId, UUID jobId);

    /**
     * Runs the three stages for the user: filter by their preferences, recall by similarity, re-rank the top by the
     * model. Cached scores are reused, so a repeat run with an unchanged resume and jobs makes no model call.
     *
     * @throws com.jobfinder.core.shared.ApiException 409 {@code resume_required} or
     *         {@code resume_embedding_pending} when there is nothing to rank with yet
     */
    RankedMatches rankedMatches(UUID userId);

    /**
     * The user's current matches read from the score cache only, for a feed that pages through more than the model's
     * re-rank window. Filters and recall run as in {@link #rankedMatches} but with at most {@code limit} jobs
     * recalled; a job with a valid cached model score comes back {@link MatchStatus#LLM_SCORED}, any other as
     * {@link MatchStatus#NOT_LLM_SCORED} with its stage-2 score and no reason (it was not asked about). It never calls
     * ai-service, spends no allowance and writes nothing. Ordered like {@link #rankedMatches}. The stats count
     * {@code recalled}, {@code considered} (the same) and {@code cached} (model-scored), and zero for the rest.
     *
     * @throws com.jobfinder.core.shared.ApiException 409 {@code resume_required} or {@code resume_embedding_pending}
     */
    RankedMatches cachedMatches(UUID userId, int limit);
}
