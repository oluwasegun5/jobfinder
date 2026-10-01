package com.jobfinder.core.feed.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jobfinder.core.feed.internal.FeedDtos.AdjustmentCode;
import com.jobfinder.core.feed.internal.FeedDtos.AdjustmentReason;
import com.jobfinder.core.jobs.JobAction;
import com.jobfinder.core.jobs.JobSignal;

/**
 * The feedback loop's arithmetic (docs/adr/0027-feed-and-feedback.md): pure, deterministic and explained. For one job
 * it looks at each of the user's signals (a saved, hidden or applied job) and asks two questions: is it the same
 * company, and is the title similar (Jaccard of the normalized words at least the threshold)? Each yes moves the job by
 * that action's points, the title points scaled by the similarity, both scaled by the signal's age
 * ({@code 0.5 ^ (age / half-life)}). Penalties and boosts are each summed and capped; the adjustment is boost minus
 * penalty, and every contribution that made it up is returned as a reason.
 */
final class FeedbackAdjuster {

    /** A signal with its title already reduced to words. */
    record Prepared(JobSignal signal, Set<String> tokens) {
    }

    /** The outcome for one job: points added to its match score (negative demotes) and why. */
    record Adjustment(double points, List<AdjustmentReason> reasons) {
        static final Adjustment NONE = new Adjustment(0, List.of());
    }

    private final FeedProperties.Feedback config;

    FeedbackAdjuster(FeedProperties.Feedback config) {
        this.config = config;
    }

    List<Prepared> prepare(List<JobSignal> signals) {
        return signals.stream().map(s -> new Prepared(s, TitleTokens.of(s.title()))).toList();
    }

    /** The adjustment of the job {@code jobId} (a signal about the job itself never moves it) as of {@code asOf}. */
    Adjustment adjust(UUID jobId, UUID companyId, Set<String> tokens, List<Prepared> signals, Instant asOf) {
        Map<AdjustmentCode, Acc> byCode = new EnumMap<>(AdjustmentCode.class);
        for (Prepared p : signals) {
            JobSignal s = p.signal();
            if (s.jobId().equals(jobId)) {
                continue;
            }
            double decay = decay(s.at(), asOf);
            if (s.companyId() != null && s.companyId().equals(companyId)) {
                byCode.computeIfAbsent(code(s.action(), true), k -> new Acc()).add(companyPoints(s.action()) * decay, s);
            }
            double similarity = TitleTokens.jaccard(tokens, p.tokens());
            if (similarity >= config.titleThreshold()) {
                byCode.computeIfAbsent(code(s.action(), false), k -> new Acc())
                        .add(titlePoints(s.action()) * similarity * decay, s);
            }
        }
        if (byCode.isEmpty()) {
            return Adjustment.NONE;
        }
        double penalty = 0;
        double boost = 0;
        for (Map.Entry<AdjustmentCode, Acc> e : byCode.entrySet()) {
            if (e.getKey().penalty()) {
                penalty += e.getValue().points;
            } else {
                boost += e.getValue().points;
            }
        }
        double appliedPenalty = Math.min(penalty, config.maxPenalty());
        double appliedBoost = Math.min(boost, config.maxBoost());
        List<AdjustmentReason> reasons = new ArrayList<>();
        // Reasons are scaled down proportionally when a cap bites, so they add up to the adjustment (plus the cap line).
        double penaltyScale = penalty > 0 ? appliedPenalty / penalty : 1;
        double boostScale = boost > 0 ? appliedBoost / boost : 1;
        for (Map.Entry<AdjustmentCode, Acc> e : byCode.entrySet()) {
            double signed = e.getKey().penalty() ? -e.getValue().points * penaltyScale : e.getValue().points * boostScale;
            reasons.add(new AdjustmentReason(e.getKey(), round2(signed), e.getValue().count, e.getValue().latestTitle));
        }
        double adjustment = appliedBoost - appliedPenalty;
        if (penalty > appliedPenalty) {
            reasons.add(new AdjustmentReason(AdjustmentCode.PENALTY_CAPPED, 0, 0, null));
        }
        if (boost > appliedBoost) {
            reasons.add(new AdjustmentReason(AdjustmentCode.BOOST_CAPPED, 0, 0, null));
        }
        return new Adjustment(adjustment, List.copyOf(reasons));
    }

    private double decay(Instant at, Instant asOf) {
        Duration age = Duration.between(at, asOf);
        if (age.isNegative()) {
            return 1;
        }
        return Math.pow(0.5, (double) age.toMillis() / config.halfLife().toMillis());
    }

    private double companyPoints(JobAction a) {
        return switch (a) {
            case HIDDEN -> config.hiddenCompany();
            case SAVED -> config.savedCompany();
            case APPLIED -> config.appliedCompany();
        };
    }

    private double titlePoints(JobAction a) {
        return switch (a) {
            case HIDDEN -> config.hiddenTitle();
            case SAVED -> config.savedTitle();
            case APPLIED -> config.appliedTitle();
        };
    }

    private static AdjustmentCode code(JobAction a, boolean company) {
        return switch (a) {
            case HIDDEN -> company ? AdjustmentCode.HIDDEN_SAME_COMPANY : AdjustmentCode.HIDDEN_SIMILAR_TITLE;
            case SAVED -> company ? AdjustmentCode.SAVED_SAME_COMPANY : AdjustmentCode.SAVED_SIMILAR_TITLE;
            case APPLIED -> company ? AdjustmentCode.APPLIED_SAME_COMPANY : AdjustmentCode.APPLIED_SIMILAR_TITLE;
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Points and count of one reason, and the title of the newest signal behind it. */
    private static final class Acc {
        double points;
        int count;
        String latestTitle;
        Instant latestAt;

        void add(double p, JobSignal s) {
            points += p;
            count++;
            if (latestAt == null || s.at().isAfter(latestAt)) {
                latestAt = s.at();
                latestTitle = s.title();
            }
        }
    }
}
