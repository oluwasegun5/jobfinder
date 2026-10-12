package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.activation.DigestSet;
import com.jobfinder.core.activation.EmailVerified;
import com.jobfinder.core.activation.IdentityNormaliser;

/**
 * The duplicate-account gate (docs/adr/0042-trial-credit-abuse-gate.md): the hard rules deny a second trial to the same
 * person, the soft signals only score (shadow mode), and claims survive account deletion unlinked.
 */
class TrialGateTests extends TrialTestSupport {

    private static final BigDecimal M1 = new BigDecimal("30");
    private static final BigDecimal M2 = new BigDecimal("70");

    @Test
    void verifyingAnEmailUnlocksTheFirstTrancheAndRecordsTheClaim() throws Exception {
        UUID id = signUpAndVerify(newEmail());

        assertThat(trialState(id)).isEqualTo("GRANTED");
        assertThat(balance(id)).isEqualByComparingTo(M1);
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_GRANT' "
                + "and idempotency_key = ?", id, "trial:" + id + ":M1")).isEqualTo(1);
        assertThat(count("select count(*) from trial_identity_claims where user_id = ? and kind = 'EMAIL' "
                + "and claimed_milestones = 1", id)).isEqualTo(1);
        assertThat(count("select count(*) from trial_accounts where user_id = ? and "
                + "expires_at = started_at + interval '30 days'", id)).isEqualTo(1);
    }

    @Test
    void deletingTheAccountAndSigningUpAgainGivesNoSecondTrial() throws Exception {
        String email = newEmail();
        UUID first = signUpAndVerify(email);
        assertThat(balance(first)).isEqualByComparingTo(M1);

        deleteAccount(email);
        assertThat(count("select count(*) from trial_identity_claims where user_id is null and kind = 'EMAIL' "
                + "and digest = ?", (Object) emailDigest(email).current().value())).isEqualTo(1);

        UUID second = signUpAndVerify(email);

        assertThat(second).isNotEqualTo(first);
        assertThat(trialState(second)).isEqualTo("DENIED");
        assertThat(trialGranted(second)).isZero();
        login(email, PASSWORD, newIp()); // the account itself works
    }

    @Test
    void gmailDotPlusAndGooglemailAliasesShareOneTrial() throws Exception {
        String local = "jane" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID original = signUpAndVerify(local + "@gmail.com");
        String dotted = local.charAt(0) + "." + local.substring(1);
        UUID withDots = signUpAndVerify(dotted + "+jobs@gmail.com");
        UUID googlemail = signUpAndVerify(local + "@googlemail.com");

        assertThat(trialState(original)).isEqualTo("GRANTED");
        assertThat(trialState(withDots)).isEqualTo("DENIED");
        assertThat(trialState(googlemail)).isEqualTo("DENIED");
        assertThat(trialGranted(withDots)).isZero();
        assertThat(trialGranted(googlemail)).isZero();
    }

    @Test
    void aReusedGoogleAccountIsDeniedEvenUnderAnotherEmail() {
        DigestSet sub = digester.googleSubject("google-sub-" + UUID.randomUUID());
        UUID first = bareVerifiedUser();
        UUID second = bareVerifiedUser();
        String email1 = newEmail();
        String email2 = newEmail();

        events.publishEvent(new EmailVerified(first, emailDigest(email1), "example.test", sub, Instant.now()));
        events.publishEvent(new EmailVerified(second, emailDigest(email2), "example.test", sub, Instant.now()));

        assertThat(trialState(first)).isEqualTo("GRANTED");
        assertThat(trialState(second)).isEqualTo("DENIED");
        assertThat(trialGranted(second)).isZero();
    }

    @Test
    void aGoogleAccountLinkedAfterTheTrialStartedIsClaimedToo() {
        DigestSet sub = digester.googleSubject("google-sub-" + UUID.randomUUID());
        UUID first = bareVerifiedUser();
        String email1 = newEmail();
        m1(first, email1, Instant.now());
        // Later the same account links Google: identity publishes M1 again, now with the Google subject.
        events.publishEvent(new EmailVerified(first, emailDigest(email1), "example.test", sub, Instant.now()));
        assertThat(trialGranted(first)).isEqualByComparingTo(M1);

        UUID second = bareVerifiedUser();
        events.publishEvent(new EmailVerified(second, emailDigest(newEmail()), "example.test", sub, Instant.now()));

        assertThat(trialState(second)).isEqualTo("DENIED");
    }

    @Test
    void anAccountWithoutAUsableClaimKeyGetsNoTrial() {
        UUID id = bareVerifiedUser();

        events.publishEvent(new EmailVerified(id, null, null, null, Instant.now()));

        assertThat(trialState(id)).isEqualTo("DENIED");
        assertThat(trialGranted(id)).isZero();
    }

    @Test
    void aDisposableDomainGetsNoTrialButTheAccountWorks() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@mailinator.com";
        UUID id = signUpAndVerify(email);

        assertThat(trialState(id)).isEqualTo("DENIED");
        assertThat(trialGranted(id)).isZero();
        assertThat(count("select count(*) from trial_identity_claims where user_id = ?", id)).isZero();
        login(email, PASSWORD, newIp());
    }

    @Test
    void aSubdomainOfADisposableDomainIsDisposableToo() {
        UUID id = bareVerifiedUser();
        String email = "x-" + UUID.randomUUID() + "@inbox.mailinator.com";

        m1(id, email, Instant.now());

        assertThat(trialState(id)).isEqualTo("DENIED");
    }

    @Test
    void theExactCvFileOfOnePriorClaimantIsDenied() {
        UUID owner = bareVerifiedUser();
        UUID copier = bareVerifiedUser();
        m1(owner, newEmail(), Instant.now());
        m1(copier, newEmail(), Instant.now());
        DigestSet file = fileDigest("cv-" + UUID.randomUUID());

        m2(owner, file, List.of(), List.of(), Instant.now());
        m2(copier, file, List.of(), List.of(), Instant.now());

        assertThat(trialGranted(owner)).isEqualByComparingTo(M1.add(M2));
        assertThat(trialState(copier)).isEqualTo("DENIED");
        assertThat(trialGranted(copier)).isEqualByComparingTo(M1); // what was already granted stays
    }

    @Test
    void aCvFileSeenOnSeveralClaimantsIsATemplateAndOnlyScores() {
        DigestSet file = fileDigest("template-" + UUID.randomUUID());
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = bareVerifiedUser();
            m1(id, newEmail(), Instant.now());
            m2(id, file, List.of(), List.of(), Instant.now());
            users.add(id);
        }

        assertThat(trialState(users.get(0))).isEqualTo("GRANTED");
        assertThat(trialState(users.get(1))).isEqualTo("DENIED"); // exactly one prior claimant
        UUID fourth = users.get(3); // three prior claimants: a template
        assertThat(trialState(fourth)).isEqualTo("GRANTED");
        assertThat(trialGranted(fourth)).isEqualByComparingTo(M1.add(M2));
        assertThat(riskScore(fourth)).isPositive();
        assertThat(count("select claimant_count from trial_identity_claims where kind = 'CV_FILE' and digest = ?",
                (Object) file.current().value())).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aCvContainingAnotherUsersEmailOnlyScoresAndNeverDenies() {
        String victimEmail = newEmail();
        UUID victim = bareVerifiedUser();
        m1(victim, victimEmail, Instant.now());
        UUID uploader = bareVerifiedUser();
        m1(uploader, newEmail(), Instant.now());

        m2(uploader, fileDigest("cv-" + UUID.randomUUID()), List.of(emailDigest(victimEmail)), List.of(),
                Instant.now());

        assertThat(trialState(uploader)).isEqualTo("GRANTED");
        assertThat(trialGranted(uploader)).isEqualByComparingTo(M1.add(M2));
        assertThat(riskScore(uploader)).isEqualTo(25);
        assertThat(trialState(victim)).isEqualTo("GRANTED");
    }

    @Test
    void anEmailFirstSeenInSomeoneElsesCvCanStillClaimItsOwnTrial() {
        String email = newEmail();
        UUID uploader = bareVerifiedUser();
        m1(uploader, newEmail(), Instant.now());
        m2(uploader, fileDigest("cv-" + UUID.randomUUID()), List.of(emailDigest(email)), List.of(), Instant.now());

        UUID owner = bareVerifiedUser();
        m1(owner, email, Instant.now());

        assertThat(trialState(owner)).isEqualTo("GRANTED");
        assertThat(trialGranted(owner)).isEqualByComparingTo(M1);
    }

    @Test
    void aSharedCvPhoneOnlyScores() {
        DigestSet phone = digester.phones("0803 " + (100 + (int) (Math.random() * 899)) + " "
                + (1000 + (int) (Math.random() * 8999))).get(0);
        UUID a = bareVerifiedUser();
        UUID b = bareVerifiedUser();
        m1(a, newEmail(), Instant.now());
        m1(b, newEmail(), Instant.now());

        m2(a, fileDigest("a-" + UUID.randomUUID()), List.of(), List.of(phone), Instant.now());
        m2(b, fileDigest("b-" + UUID.randomUUID()), List.of(), List.of(phone), Instant.now());

        assertThat(trialState(b)).isEqualTo("GRANTED");
        assertThat(riskScore(b)).isEqualTo(30);
    }

    @Test
    void inShadowModeAHighScoreStillGrants() {
        String victimEmail = newEmail();
        UUID victim = bareVerifiedUser();
        m1(victim, victimEmail, Instant.now());
        DigestSet phone = digester.phones("0809 " + (100 + (int) (Math.random() * 899)) + " "
                + (1000 + (int) (Math.random() * 8999))).get(0);
        m2(victim, fileDigest("v-" + UUID.randomUUID()), List.of(), List.of(phone), Instant.now());
        UUID suspect = bareVerifiedUser();
        m1(suspect, newEmail(), Instant.now());

        m2(suspect, fileDigest("s-" + UUID.randomUUID()), List.of(emailDigest(victimEmail)), List.of(phone),
                Instant.now());

        assertThat(riskScore(suspect)).isGreaterThanOrEqualTo(55);
        assertThat(trialState(suspect)).isEqualTo("GRANTED");
        assertThat(trialGranted(suspect)).isEqualByComparingTo(M1.add(M2));
    }

    @Test
    void concurrentClaimsForTheSameIdentityGrantOnce() throws Exception {
        String local = "race" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        int n = 8;
        List<UUID> users = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            users.add(bareVerifiedUser());
            aliases.add(local.substring(0, 1) + ".".repeat(i % 2) + local.substring(1) + "+" + i
                    + (i % 3 == 0 ? "@googlemail.com" : "@gmail.com"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = users.get(i);
            String email = aliases.get(i);
            futures.add(pool.submit(() -> {
                start.await();
                m1(id, email, Instant.now());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long granted = users.stream().filter(u -> "GRANTED".equals(trialState(u))).count();
        long denied = users.stream().filter(u -> "DENIED".equals(trialState(u))).count();
        assertThat(granted).isEqualTo(1);
        assertThat(denied).isEqualTo(n - 1);
        assertThat(users.stream().mapToInt(u -> count("select count(*) from credit_ledger where "
                + "reason = 'TRIAL_GRANT' and user_id = ?", u)).sum()).isEqualTo(1);
        assertThat(count("select count(*) from trial_identity_claims where kind = 'EMAIL' and digest = ?",
                (Object) emailDigest(local + "@gmail.com").current().value())).isEqualTo(1);
    }

    @Test
    void deletionUnlinksTheClaimsAndErasesEverythingElse() throws Exception {
        String email = newEmail();
        UUID id = signUpAndVerify(email);
        DigestSet phone = digester.phones("0701 " + (100 + (int) (Math.random() * 899)) + " "
                + (1000 + (int) (Math.random() * 8999))).get(0);
        m2(id, fileDigest("del-" + UUID.randomUUID()), List.of(emailDigest(newEmail())), List.of(phone),
                Instant.now());
        Map<String, Object> before = jdbc.queryForMap("select count(*) as n, "
                + "string_agg(kind, ',' order by kind) as kinds from trial_identity_claims where user_id = ?", id);
        assertThat(before.get("kinds")).isEqualTo("CV_EMAIL,CV_FILE,CV_PHONE,EMAIL");

        deleteAccount(email);

        assertThat(count("select count(*) from trial_identity_claims where user_id = ?", id)).isZero();
        assertThat(count("select count(*) from trial_accounts where user_id = ?", id)).isZero();
        assertThat(count("select count(*) from credit_ledger where user_id = ?", id)).isZero();
        assertThat(count("select count(*) from users where id = ?", id)).isZero();
        // The four claims are still there, without a link to anyone: kind, digest, version and dates only.
        assertThat(count("select count(*) from trial_identity_claims where user_id is null and kind = 'EMAIL' "
                + "and digest = ?", (Object) emailDigest(email).current().value())).isEqualTo(1);
        assertThat(count("select count(*) from trial_identity_claims where user_id is null and kind = 'CV_PHONE' "
                + "and digest = ?", (Object) phone.current().value())).isEqualTo(1);
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_name = 'trial_identity_claims' order by column_name", String.class);
        assertThat(columns).containsExactlyInAnyOrder("id", "kind", "digest", "key_version", "user_id",
                "claimed_milestones", "claimant_count", "first_claimed_at", "last_seen_at", "expires_at");
    }

    @Test
    void theClaimIsKeyedByTheCanonicalEmail() {
        assertThat(emailDigest("A.B+x@googlemail.com").current().value())
                .isEqualTo(digester.email("ab@gmail.com").orElseThrow().current().value());
        assertThat(IdentityNormaliser.email("A.B+x@googlemail.com")).contains("ab@gmail.com");
    }

    @Test
    void aMilestoneIsGrantedOnceHoweverOftenItsEventArrives() {
        UUID id = bareVerifiedUser();
        String email = newEmail();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        m1(id, email, now);
        m1(id, email, now.plusSeconds(5));

        assertThat(trialGranted(id)).isEqualByComparingTo(M1);
        assertThat(trialState(id)).isEqualTo("GRANTED");
    }
}
