package com.jobfinder.core.billing.internal;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionTask;

/**
 * Admin decisions on trial accounts, with the admin's note, are kept 24 months and then purged
 * (docs/adr/0047-trial-review-queue.md, section 5). {@code app.billing.trial.review-retention} can lengthen the period;
 * the database refuses to delete a decision younger than 730 days, so a shorter setting is raised to that floor.
 */
@Component
class TrialReviewRetention implements RetentionTask {

    static final Duration MINIMUM = Duration.ofDays(730);

    private final TrialReviewStore reviews;
    private final Duration retention;

    TrialReviewRetention(TrialReviewStore reviews,
            @Value("${app.billing.trial.review-retention:P730D}") Duration retention) {
        this.reviews = reviews;
        this.retention = retention.compareTo(MINIMUM) < 0 ? MINIMUM : retention;
    }

    @Override
    public String name() {
        return "trial-review-decisions";
    }

    @Override
    public int purge(Instant now) {
        return reviews.purgeDecisionsBefore(now.minus(retention));
    }
}
