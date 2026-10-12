package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The staged one-time trial and its duplicate-account gate ({@code app.billing.trial.*},
 * docs/adr/0042-trial-credit-abuse-gate.md).
 *
 * @param enabled         grants trial credits on the milestone events; off, the events are ignored
 * @param tranches        credits per milestone
 * @param validity        how long after M1 the trial credits last (then the unspent rest expires)
 * @param dailyCapCredits the daily cap while the trial runs (lower than the paid cap)
 * @param hourlyCeiling   circuit breaker: more trial grants than this in the last hour sends new claims to HOLD (logged
 *                        only while the score is in shadow mode)
 * @param scoreEnforced   false (shadow mode): the risk score is computed and logged, and a non-hard match always
 *                        grants; true: HOLD and DENY by score
 * @param score           the soft signals' weights and the thresholds
 * @param claimRetention  claims are purged this long after they were last seen (12 months)
 * @param disposable      the disposable-email-domain list
 * @param seed            the one-off seed of accounts that existed before the trial
 */
@ConfigurationProperties("app.billing.trial")
record TrialProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue Tranches tranches,
        @DefaultValue("30d") Duration validity,
        @DefaultValue("150") BigDecimal dailyCapCredits,
        @DefaultValue("200") int hourlyCeiling,
        @DefaultValue("false") boolean scoreEnforced,
        @DefaultValue Score score,
        @DefaultValue("365d") Duration claimRetention,
        @DefaultValue Disposable disposable,
        @DefaultValue Seed seed) {

    TrialProperties {
        if (validity == null || validity.isNegative() || validity.isZero()) {
            throw new IllegalArgumentException("app.billing.trial.validity must be positive");
        }
        if (dailyCapCredits == null || dailyCapCredits.signum() < 0) {
            throw new IllegalArgumentException("app.billing.trial.daily-cap-credits must be 0 (no cap) or more");
        }
        if (claimRetention == null || claimRetention.isNegative() || claimRetention.isZero()) {
            throw new IllegalArgumentException("app.billing.trial.claim-retention must be positive");
        }
        if (hourlyCeiling < 1) {
            throw new IllegalArgumentException("app.billing.trial.hourly-ceiling must be at least 1");
        }
    }

    BigDecimal tranche(TrialMilestone milestone) {
        return switch (milestone) {
            case EMAIL_VERIFIED -> tranches.m1();
            case CV_PARSED -> tranches.m2();
            case ONBOARDING_COMPLETED -> tranches.m3();
            case MATCHES_VIEWED -> tranches.m4();
        };
    }

    record Tranches(@DefaultValue("30") BigDecimal m1, @DefaultValue("70") BigDecimal m2,
            @DefaultValue("100") BigDecimal m3, @DefaultValue("100") BigDecimal m4) {
        Tranches {
            for (BigDecimal t : new BigDecimal[] {m1, m2, m3, m4}) {
                if (t == null || t.signum() < 0) {
                    throw new IllegalArgumentException("app.billing.trial.tranches.* must be 0 or more");
                }
            }
        }
    }

    /**
     * Soft-signal weights (points) and thresholds: below {@code holdAt} grants, from {@code holdAt} holds, from
     * {@code denyAt} denies (only when the score is enforced).
     */
    record Score(@DefaultValue("25") int cvEmail,
            @DefaultValue("30") int cvPhone,
            @DefaultValue("20") int cvFileShared,
            @DefaultValue("10") int fastOnboarding,
            @DefaultValue("2m") Duration fastOnboardingWithin,
            @DefaultValue("-5") int googleSignIn,
            @DefaultValue("-10") int cvEmailIsAccountEmail,
            @DefaultValue("30") int holdAt,
            @DefaultValue("60") int denyAt) {
    }

    /**
     * The disposable-email-domain list: the copy shipped in the jar, replaced by the last good weekly download when
     * {@code refreshEnabled}. {@code allow} and {@code deny} (domain names) override the list.
     */
    record Disposable(@DefaultValue("false") boolean refreshEnabled,
            @DefaultValue("https://raw.githubusercontent.com/disposable-email-domains/disposable-email-domains/main/disposable_email_blocklist.conf")
            String url,
            @DefaultValue("0 30 3 * * MON") String refreshCron,
            @DefaultValue("0.2") double maxChange,
            @DefaultValue("20s") Duration timeout,
            @DefaultValue("1h") Duration reloadInterval,
            @DefaultValue List<String> allow,
            @DefaultValue List<String> deny) {
    }

    /** The seed of existing accounts runs once at startup (under a lock, resumable) unless switched off. */
    record Seed(@DefaultValue("true") boolean enabled, @DefaultValue("500") int batchSize) {
    }
}
