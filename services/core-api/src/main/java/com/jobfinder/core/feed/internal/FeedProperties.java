package com.jobfinder.core.feed.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The feed's settings ({@code app.feed.*}, docs/adr/0027-feed-and-feedback.md).
 *
 * @param poolSize how many of the user's best-matching jobs (by the matching recall) the feed ranks and pages through
 * @param feedback how saves, hides and applications move jobs; see {@link Feedback}
 */
@ConfigurationProperties("app.feed")
record FeedProperties(@DefaultValue("100") int poolSize, @DefaultValue Feedback feedback) {

    /** Hard ceiling of the adjustment caps: a penalty this large still leaves every job in the feed. */
    static final double MAX_CAP = 50;

    FeedProperties {
        if (poolSize < 1 || poolSize > 300) {
            throw new IllegalArgumentException("app.feed.pool-size must be between 1 and 300");
        }
    }

    /**
     * Feedback is the user's own actions, newest first, at most {@code maxSignalsPerAction} of each kind and none older
     * than {@code window}; a signal counts for half as much for every {@code halfLife} of age. A job that shares the
     * signal's company, or whose normalized title has a Jaccard similarity of at least {@code titleThreshold} to the
     * signal's, is moved by that action's points (the title points scaled by the similarity). Penalties and boosts are
     * each summed and then capped, so one hide can never remove a company from the feed and a pile of hides can never
     * move a job more than {@code maxPenalty} points (and boosts at most {@code maxBoost}).
     */
    record Feedback(
            @DefaultValue("90d") Duration window,
            @DefaultValue("30d") Duration halfLife,
            @DefaultValue("100") int maxSignalsPerAction,
            @DefaultValue("0.5") double titleThreshold,
            @DefaultValue("15") double hiddenCompany,
            @DefaultValue("15") double hiddenTitle,
            @DefaultValue("5") double savedCompany,
            @DefaultValue("6") double savedTitle,
            @DefaultValue("4") double appliedCompany,
            @DefaultValue("5") double appliedTitle,
            @DefaultValue("30") double maxPenalty,
            @DefaultValue("15") double maxBoost) {

        Feedback {
            if (window.isZero() || window.isNegative() || halfLife.isZero() || halfLife.isNegative()) {
                throw new IllegalArgumentException("app.feed.feedback.window and half-life must be positive");
            }
            if (maxSignalsPerAction < 1 || maxSignalsPerAction > 1000) {
                throw new IllegalArgumentException("app.feed.feedback.max-signals-per-action must be between 1 and 1000");
            }
            if (!(titleThreshold > 0 && titleThreshold <= 1)) {
                throw new IllegalArgumentException("app.feed.feedback.title-threshold must be above 0 and at most 1");
            }
            for (double points : new double[] { hiddenCompany, hiddenTitle, savedCompany, savedTitle, appliedCompany,
                    appliedTitle }) {
                if (!(points >= 0 && points <= 100)) {
                    throw new IllegalArgumentException("app.feed.feedback points must be between 0 and 100");
                }
            }
            if (!(maxPenalty >= 0 && maxPenalty <= MAX_CAP) || !(maxBoost >= 0 && maxBoost <= MAX_CAP)) {
                throw new IllegalArgumentException(
                        "app.feed.feedback.max-penalty and max-boost must be between 0 and " + (int) MAX_CAP
                                + ": feedback may reorder the feed but must never be able to empty it");
            }
        }
    }
}
