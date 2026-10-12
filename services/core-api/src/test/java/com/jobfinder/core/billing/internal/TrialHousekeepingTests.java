package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.identity.UserDataBundle;

/** The trial's data duties: export, retention, the seed of existing accounts, and the disposable-domain list. */
class TrialHousekeepingTests extends TrialTestSupport {

    @Autowired
    private BillingDataExporter exporter;

    @Autowired
    private TrialClaimRetention retention;

    @Autowired
    private TrialSeedJob seed;

    @Autowired
    private DisposableDomains disposable;

    @Autowired
    private DisposableDomainRefresher refresher;

    private Map<String, String> export(UUID userId) {
        Map<String, String> files = new HashMap<>();
        exporter.export(userId, new UserDataBundle() {
            @Override
            public void json(String name, String json) {
                files.put(name, json);
            }

            @Override
            public void file(String path, byte[] content) {
            }

            @Override
            public void skipped(String path, String reason) {
            }
        });
        return files;
    }

    @Test
    void theExportListsClaimKindsAndDatesButNoDigest() {
        UUID id = bareVerifiedUser();
        m1(id, newEmail(), Instant.now());

        Map<String, String> files = export(id);

        assertThat(files.get("trial-claims")).contains("\"kind\"", "EMAIL", "first_claimed_at", "last_seen_at",
                "expires_at").doesNotContain("digest", "key_version");
        assertThat(files.get("trial")).contains("GRANTED", "risk_score", "expires_at");
    }

    @Test
    void claimsArePurgedTwelveMonthsAfterTheyWereLastSeen() {
        UUID id = bareVerifiedUser();
        m1(id, newEmail(), Instant.now());
        UUID stale = bareVerifiedUser();
        m1(stale, newEmail(), Instant.now());
        jdbc.update("update trial_identity_claims set last_seen_at = now() - interval '13 months', "
                + "expires_at = now() - interval '1 month' where user_id = ?", stale);

        int purged = retention.purge(Instant.now());

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from trial_identity_claims where user_id = ?", stale)).isZero();
        assertThat(count("select count(*) from trial_identity_claims where user_id = ? and "
                + "expires_at > now() + interval '364 days'", id)).isEqualTo(1);
        assertThat(retention.name()).isEqualTo("trial-claims");
    }

    @Test
    void accountsVerifiedBeforeTheTrialAreSeededSoTheyCannotReTrial() throws Exception {
        String email = newEmail();
        // An existing account: verified before the trial existed, so no milestone event ever ran for it.
        UUID legacy = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, password_hash, email_verified_at, created_at, updated_at,
                                   ai_consent_version, ai_consent_at)
                values (?, ?, null, now() - interval '1 day', now(), now(), '1', now())
                """, legacy, email);
        jdbc.update("update trial_seed set cutoff = now(), completed_at = null");

        int seeded = seed.run();
        int again = seed.run();

        assertThat(seeded).isGreaterThanOrEqualTo(1);
        assertThat(again).isZero();
        assertThat(trialState(legacy)).isEqualTo("LEGACY");
        assertThat(count("select claimed_milestones from trial_identity_claims where user_id = ? and kind = 'EMAIL'",
                legacy)).isEqualTo(15);
        assertThat(trialGranted(legacy)).isZero();

        // The existing account deleted and signed up again: the seeded claim denies the trial.
        jdbc.update("delete from users where id = ?", legacy);
        UUID again2 = signUpAndVerify(email);
        assertThat(trialState(again2)).isEqualTo("DENIED");
    }

    @Test
    void theShippedListIsUsedAndAllowAndDenyListsOverrideIt() {
        assertThat(disposable.isDisposable("mailinator.com")).isTrue();
        assertThat(disposable.isDisposable("inbox.mailinator.com")).isTrue();
        assertThat(disposable.isDisposable("gmail.com")).isFalse();
        assertThat(disposable.isDisposable("example.test")).isFalse();
        assertThat(disposable.size()).isGreaterThan(1000);
    }

    @Test
    void theRefreshKeepsTheLastGoodCopyWhenTheNewOneIsEmptyOrChangesTooMuch() {
        int size = disposable.size();
        String current = String.join("\n", disposable.domains());
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        assertThat(disposable.apply("", "test", now)).isEqualTo(DisposableDomains.Outcome.REJECTED_EMPTY);
        assertThat(disposable.apply("# comment only\n", "test", now))
                .isEqualTo(DisposableDomains.Outcome.REJECTED_EMPTY);
        assertThat(disposable.apply("one.example\ntwo.example\n", "test", now))
                .isEqualTo(DisposableDomains.Outcome.REJECTED_TOO_DIFFERENT);
        assertThat(disposable.size()).isEqualTo(size);

        String extra = "fresh-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
        assertThat(disposable.apply(current + "\n" + extra + "\n", "test", now))
                .isEqualTo(DisposableDomains.Outcome.UPDATED);
        assertThat(disposable.isDisposable(extra)).isTrue();
        assertThat(count("select domain_count from disposable_domain_list where id = 1")).isEqualTo(size + 1);
    }

    @Test
    void theRefreshNeverFetchesWhenSwitchedOff() {
        assertThat(refresher.refresh()).isEqualTo(DisposableDomains.Outcome.DISABLED);
        assertThat(List.of(DisposableDomains.Outcome.values())).contains(DisposableDomains.Outcome.FETCH_FAILED);
    }
}
