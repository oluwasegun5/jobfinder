package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.activation.FirstMatchesViewed;
import com.jobfinder.core.activation.OnboardingCompleted;
import com.jobfinder.core.billing.Allowance;

/** The staged trial over time: tranches, the M4 day rule, the trial cap, expiry, the status API and the free grant. */
class TrialLifecycleTests extends TrialTestSupport {

    @Autowired
    private CreditGrants grants;

    @Autowired
    private BillingJobs jobs;

    @Autowired
    private DailyCapService caps;

    /** Noon UTC two days ago, so "the same UTC day" and "the next UTC day" are both in the past. */
    private static Instant twoDaysAgoNoon() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS);
    }

    @Test
    void eachMilestoneReleasesItsTrancheOnceAndM4OnlyOnALaterUtcDay() {
        UUID id = bareVerifiedUser();
        String email = newEmail();
        Instant m1 = twoDaysAgoNoon();

        m1(id, email, m1);
        m2(id, fileDigest("cv-" + UUID.randomUUID()), List.of(), List.of(), m1.plusSeconds(600));
        events.publishEvent(new OnboardingCompleted(id, m1.plusSeconds(900)));
        events.publishEvent(new FirstMatchesViewed(id, m1.plusSeconds(1200), true));
        assertThat(trialGranted(id)).isEqualByComparingTo("200");

        events.publishEvent(new FirstMatchesViewed(id, m1.plus(1, ChronoUnit.DAYS), false));
        events.publishEvent(new FirstMatchesViewed(id, m1.plus(1, ChronoUnit.DAYS).plusSeconds(60), false));
        events.publishEvent(new OnboardingCompleted(id, m1.plus(1, ChronoUnit.DAYS)));

        assertThat(trialGranted(id)).isEqualByComparingTo("300");
        assertThat(count("select milestones from trial_accounts where user_id = ?", id)).isEqualTo(15);
        assertThat(count("select claimed_milestones from trial_identity_claims where user_id = ? and kind = 'EMAIL'",
                id)).isEqualTo(15);
    }

    @Test
    void finishingOnboardingWithinTwoMinutesOfM1OnlyScores() {
        UUID id = bareVerifiedUser();
        Instant m1 = Instant.now().minusSeconds(30);
        m1(id, newEmail(), m1);
        m2(id, fileDigest("fast-" + UUID.randomUUID()), List.of(), List.of(), m1.plusSeconds(20));

        events.publishEvent(new OnboardingCompleted(id, m1.plusSeconds(60)));

        assertThat(riskScore(id)).isEqualTo(10);
        assertThat(trialGranted(id)).isEqualByComparingTo("200");
    }

    @Test
    void milestonesBeforeM1GrantNothing() {
        UUID id = bareVerifiedUser();

        events.publishEvent(new OnboardingCompleted(id, Instant.now()));

        assertThat(trialState(id)).isNull();
        assertThat(trialGranted(id)).isZero();
    }

    @Test
    void theTrialDailyCapAppliesWhileTheTrialRuns() {
        UUID id = bareVerifiedUser();
        m1(id, newEmail(), Instant.now());

        Allowance allowance = caps.allowance(id, Instant.now());

        assertThat(allowance.dailyCap()).isEqualByComparingTo("150");
        UUID other = bareVerifiedUser();
        assertThat(caps.allowance(other, Instant.now()).dailyCap()).isEqualByComparingTo("500");
    }

    @Test
    void unspentTrialCreditsExpireAfterTheValidityOnce() {
        UUID id = bareVerifiedUser();
        Instant m1 = Instant.now().minus(31, ChronoUnit.DAYS);
        m1(id, newEmail(), m1);
        assertThat(balance(id)).isEqualByComparingTo("30");

        int expired = jobs.expireTrials(Instant.now());
        jobs.expireTrials(Instant.now());

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(balance(id)).isZero();
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_EXPIRY' "
                + "and delta = -30 and idempotency_key = ?", id, "trial-expiry:" + id)).isEqualTo(1);
        assertThat(count("select count(*) from trial_accounts where user_id = ? and ended_at is not null", id))
                .isEqualTo(1);
        // A milestone after the trial ended unlocks nothing.
        events.publishEvent(new OnboardingCompleted(id, Instant.now()));
        assertThat(trialGranted(id)).isEqualByComparingTo("30");
        assertThat(caps.allowance(id, Instant.now()).dailyCap()).isEqualByComparingTo("500");
    }

    @Test
    void theExpiryOnlyTakesWhatIsLeftOfTheTrial() {
        UUID id = bareVerifiedUser();
        m1(id, newEmail(), Instant.now().minus(31, ChronoUnit.DAYS));
        jdbc.update("""
                insert into credit_ledger (user_id, delta, reason, balance_after, topup_after, created_at)
                values (?, -12, 'AI_USAGE', 18, 0, now())
                """, id);

        jobs.expireTrials(Instant.now());

        assertThat(balance(id)).isZero();
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_EXPIRY' "
                + "and delta = -18", id)).isEqualTo(1);
    }

    @Test
    void theMonthlyFreeGrantIsOffByDefault() {
        UUID id = bareVerifiedUser();

        assertThat(grants.grantFreeIfDue(id, Instant.now())).isFalse();
        assertThat(jobs.grantFree(Instant.now())).isZero();
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'PLAN_GRANT'", id))
                .isZero();
    }

    @Test
    void theStatusApiShowsTheTrial() throws Exception {
        String email = newEmail();
        UUID id = signUpAndVerify(email);
        Session session = login(email, PASSWORD, newIp());
        Instant startedAt = jdbc.queryForObject("select started_at from trial_accounts where user_id = ?",
                java.sql.Timestamp.class, id).toInstant();

        mvc.perform(get("/billing/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trial.state").value("GRANTED"))
                .andExpect(jsonPath("$.trial.unlocked").value(30))
                .andExpect(jsonPath("$.trial.remaining").value(30))
                .andExpect(jsonPath("$.trial.milestones[0]").value("EMAIL_VERIFIED"))
                .andExpect(jsonPath("$.trial.expiresAt").value(
                        startedAt.plus(30, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC).toInstant().toString()))
                .andExpect(jsonPath("$.balance").value(30))
                .andExpect(jsonPath("$.allowance.dailyCap").value(150));
    }

    @Test
    void aDeniedTrialShowsAsDeniedWithoutSayingWhy() throws Exception {
        String email = "x-" + UUID.randomUUID() + "@mailinator.com";
        signUpAndVerify(email);
        Session session = login(email, PASSWORD, newIp());

        mvc.perform(get("/billing/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trial.state").value("DENIED"))
                .andExpect(jsonPath("$.trial.unlocked").value(0))
                .andExpect(jsonPath("$.trial.reason").doesNotExist());
    }

    @Test
    void aUserWhoHasNotVerifiedYetIsPending() {
        UUID id = bareVerifiedUser();

        assertThat(billingTrial(id).state()).isEqualTo("PENDING");
        assertThat(billingTrial(id).unlocked()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Autowired
    private TrialService trials;

    private TrialService.TrialView billingTrial(UUID id) {
        return trials.view(id, Instant.now());
    }
}
