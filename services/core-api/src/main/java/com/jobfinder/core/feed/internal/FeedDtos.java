package com.jobfinder.core.feed.internal;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jobfinder.core.jobs.JobCard;
import com.jobfinder.core.matching.FallbackReason;

/** Response shapes of {@code GET /feed}. Nothing here carries a user ID; absent values are omitted. */
final class FeedDtos {

    /** The largest page a client can ask for, and the default. */
    static final int MAX_LIMIT = 50;
    static final int DEFAULT_LIMIT = 20;

    private FeedDtos() {
    }

    /** Where {@code matchScore} came from: the model, or the coarser estimate from embeddings, skills and recency. */
    enum ScoreSource {
        LLM_SCORED, STAGE2_ONLY
    }

    /** Why the first page of a feed is empty. */
    enum EmptyReason {
        /** No primary resume with parsed content: upload one. */
        NO_RESUME,
        /** A resume but no saved preferences: the feed is made only for users who set them. */
        NO_PREFERENCES,
        /** The resume is still being analysed (embedding pending): try again shortly. */
        RESUME_PROCESSING,
        /** Nothing matches the preferences right now (or everything was hidden or applied to). */
        NO_MATCHES
    }

    /** What moved a job, and by how much. {@code count} signals of that kind, {@code example} the newest one's title. */
    enum AdjustmentCode {
        HIDDEN_SAME_COMPANY(true), HIDDEN_SIMILAR_TITLE(true), SAVED_SAME_COMPANY(false), SAVED_SIMILAR_TITLE(false),
        APPLIED_SAME_COMPANY(false), APPLIED_SIMILAR_TITLE(false),
        /** The summed penalties exceeded the cap; the adjustment is the cap. */
        PENALTY_CAPPED(true),
        /** The summed boosts exceeded the cap; the adjustment is the cap. */
        BOOST_CAPPED(false);

        private final boolean penalty;

        AdjustmentCode(boolean penalty) {
            this.penalty = penalty;
        }

        boolean penalty() {
            return penalty;
        }
    }

    /**
     * One contribution to a job's adjustment. {@code points} is signed (negative demotes) and already scaled to the
     * cap, so the reasons add up to the adjustment. The two {@code *_CAPPED} entries have 0 points and only say a cap
     * applied.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AdjustmentReason(AdjustmentCode code, double points, int count, String example) {
    }

    /**
     * One job in the feed.
     *
     * @param matchScore     0 to 100: the model's score when {@code scoreSource} is {@code LLM_SCORED}, otherwise the
     *                       estimate (show it as an estimate)
     * @param feedScore      {@code matchScore} plus {@code adjustment}, kept within 0 to 100: what the order is by
     * @param adjustment     points the user's saves, hides and applications added (negative: took away)
     * @param reasons        what the adjustment is made of; empty when it is 0
     * @param fallbackReason why there is no model score, when it is known (cap reached, model unavailable, expired)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FeedItem(JobCard job, int matchScore, ScoreSource scoreSource, double feedScore, double adjustment,
            List<AdjustmentReason> reasons, List<String> strengths, List<String> gaps, FallbackReason fallbackReason,
            String model, Instant scoredAt) {
    }

    /**
     * A page of the feed, best first. {@code nextCursor} is absent on the last page; pass it back unchanged.
     * {@code emptyReason} is set only when a first page has no items.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FeedPage(List<FeedItem> items, String nextCursor, EmptyReason emptyReason) {
    }
}
