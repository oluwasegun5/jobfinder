package com.jobfinder.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** PLAN.md section 6: missing for 2 consecutive runs expires a job; an aggregator job unseen for 45 days does too. */
class JobExpiryTests extends JobPipelineTestSupport {

    private RawPosting a() {
        return posting("a", "Engineer", "Acme", "Lagos");
    }

    private RawPosting b() {
        return posting("b", "Designer", "Acme", "Lagos");
    }

    @Test
    void anAtsJobMissingOnceStaysActiveAndMissingTwiceInARowExpires() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", a(), b());
        ingestion.runNow(FAKE).orElseThrow();

        fake.customPostings("acme", a());
        IngestionRunSummary second = ingestion.runNow(FAKE).orElseThrow();
        assertThat(second.expired()).isZero();
        assertThat(statusOf(FAKE, "b")).isEqualTo("ACTIVE");
        assertThat(missedRuns(FAKE, "b")).isEqualTo(1);
        assertThat(missedRuns(FAKE, "a")).isZero();

        IngestionRunSummary third = ingestion.runNow(FAKE).orElseThrow();
        assertThat(third.expired()).isEqualTo(1);
        assertThat(statusOf(FAKE, "b")).isEqualTo("EXPIRED");
        assertThat(statusOf(FAKE, "a")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select expired from ingestion_runs where id = ?", Integer.class,
                third.runId())).isEqualTo(1);

        IngestionRunSummary fourth = ingestion.runNow(FAKE).orElseThrow();
        assertThat(fourth.expired()).as("an expired job is not expired again").isZero();
    }

    @Test
    void beingSeenAgainResetsTheCountSoOnlyConsecutiveMissesExpire() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", a(), b());
        ingestion.runNow(FAKE).orElseThrow();
        fake.customPostings("acme", a());
        ingestion.runNow(FAKE).orElseThrow();
        assertThat(missedRuns(FAKE, "b")).isEqualTo(1);

        fake.customPostings("acme", a(), b());
        ingestion.runNow(FAKE).orElseThrow();
        assertThat(missedRuns(FAKE, "b")).isZero();

        fake.customPostings("acme", a());
        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();
        assertThat(summary.expired()).isZero();
        assertThat(statusOf(FAKE, "b")).isEqualTo("ACTIVE");
        assertThat(missedRuns(FAKE, "b")).isEqualTo(1);
    }

    @Test
    void anExpiredJobIsReactivatedWhenASourceListsItAgain() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", a(), b());
        ingestion.runNow(FAKE).orElseThrow();
        fake.customPostings("acme", a());
        ingestion.runNow(FAKE).orElseThrow();
        ingestion.runNow(FAKE).orElseThrow();
        assertThat(statusOf(FAKE, "b")).isEqualTo("EXPIRED");

        fake.customPostings("acme", a(), b());
        ingestion.runNow(FAKE).orElseThrow();

        assertThat(statusOf(FAKE, "b")).isEqualTo("ACTIVE");
        assertThat(missedRuns(FAKE, "b")).isZero();
    }

    @Test
    void aTargetThatFailedIsNotHeldAgainstItsJobs() {
        addTarget(FAKE, "one");
        addTarget(FAKE, "two");
        fake.customPostings("one", a()).customPostings("two", b());
        ingestion.runNow(FAKE).orElseThrow();

        fake.failPermanently("two");
        ingestion.runNow(FAKE).orElseThrow();
        ingestion.runNow(FAKE).orElseThrow();
        ingestion.runNow(FAKE).orElseThrow();

        assertThat(missedRuns(FAKE, "b")).as("its fetch never succeeded, so nothing was missed").isZero();
        assertThat(statusOf(FAKE, "b")).isEqualTo("ACTIVE");
    }

    @Test
    void anAdapterThatIsNotAFullListingNeverExpiresJobsByAbsence() {
        addTarget(FAKE, "acme");
        fake.customPostings("acme", a()).fullListing(false);
        ingestion.runNow(FAKE).orElseThrow();

        fake.customPostings("acme");
        for (int i = 0; i < 4; i++) {
            ingestion.runNow(FAKE).orElseThrow();
        }

        assertThat(statusOf(FAKE, "a")).isEqualTo("ACTIVE");
        assertThat(missedRuns(FAKE, "a")).isZero();
    }

    @Test
    void anAggregatorJobIsNotExpiredByMissedRunsButByFortyFiveDaysWithoutARefresh() {
        addTarget(FAKE_AGG, "search");
        fakeAgg.customPostings("search", a());
        ingestion.runNow(FAKE_AGG).orElseThrow();

        fakeAgg.customPostings("search");
        for (int i = 0; i < 3; i++) {
            assertThat(ingestion.runNow(FAKE_AGG).orElseThrow().expired()).isZero();
        }
        assertThat(statusOf(FAKE_AGG, "a")).as("search results rotate: missing is not gone").isEqualTo("ACTIVE");
        assertThat(missedRuns(FAKE_AGG, "a")).isZero();

        backdateLastSeen(FAKE_AGG, "a", 44);
        assertThat(ingestion.runNow(FAKE_AGG).orElseThrow().expired()).isZero();
        assertThat(statusOf(FAKE_AGG, "a")).isEqualTo("ACTIVE");

        backdateLastSeen(FAKE_AGG, "a", 46);
        IngestionRunSummary summary = ingestion.runNow(FAKE_AGG).orElseThrow();
        assertThat(summary.expired()).isEqualTo(1);
        assertThat(statusOf(FAKE_AGG, "a")).isEqualTo("EXPIRED");
    }

    @Test
    void anAggregatorJobSeenInEveryRunNeverGoesStale() {
        addTarget(FAKE_AGG, "search");
        fakeAgg.customPostings("search", a());
        ingestion.runNow(FAKE_AGG).orElseThrow();
        backdateLastSeen(FAKE_AGG, "a", 46);

        IngestionRunSummary summary = ingestion.runNow(FAKE_AGG).orElseThrow();

        assertThat(summary.expired()).isZero();
        assertThat(statusOf(FAKE_AGG, "a")).isEqualTo("ACTIVE");
    }

    @Test
    void aJobListedByTwoSourcesStaysActiveUntilNeitherListsItAnymore() {
        addTarget(FAKE, "acme");
        addTarget(FAKE_AGG, "search");
        fake.customPostings("acme", a());
        fakeAgg.customPostings("search", a());
        ingestion.runNow(FAKE).orElseThrow();
        ingestion.runNow(FAKE_AGG).orElseThrow();
        assertThat(jobIdOf(FAKE, "a")).isEqualTo(jobIdOf(FAKE_AGG, "a"));

        fake.customPostings("acme");
        ingestion.runNow(FAKE).orElseThrow();
        IngestionRunSummary ats = ingestion.runNow(FAKE).orElseThrow();
        assertThat(missedRuns(FAKE, "a")).isEqualTo(2);
        assertThat(ats.expired()).as("the aggregator still lists it").isZero();
        assertThat(statusOf(FAKE, "a")).isEqualTo("ACTIVE");

        fakeAgg.customPostings("search");
        backdateLastSeen(FAKE_AGG, "a", 46);
        IngestionRunSummary aggregator = ingestion.runNow(FAKE_AGG).orElseThrow();
        assertThat(aggregator.expired()).isEqualTo(1);
        assertThat(statusOf(FAKE, "a")).isEqualTo("EXPIRED");
    }

    @Test
    void aJobWhoseOwnExpiryDateHasPassedExpiresEvenThoughItIsStillListed() {
        addTarget(FAKE, "acme");
        Instant future = Instant.now().plusSeconds(86_400);
        fake.customPostings("acme", posting("a", "Engineer", "Acme", "Lagos", "expiresAt", future.toString()));
        ingestion.runNow(FAKE).orElseThrow();
        assertThat(statusOf(FAKE, "a")).isEqualTo("ACTIVE");

        jdbc.update("update jobs set expires_at = now() - interval '1 day' where id = ?", jobIdOf(FAKE, "a"));
        fake.customPostings("acme");
        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.expired()).isEqualTo(1);
        assertThat(missedRuns(FAKE, "a")).as("expired by date, not by absence").isEqualTo(1);
        assertThat(statusOf(FAKE, "a")).isEqualTo("EXPIRED");
    }

    @Test
    void aPostingThatArrivesAlreadyPastItsExpiryDateIsStoredAsExpired() {
        addTarget(FAKE, "acme");
        Instant past = Instant.now().minusSeconds(86_400);
        fake.customPostings("acme", posting("a", "Engineer", "Acme", "Lagos", "expiresAt", past.toString()));

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.created()).isEqualTo(1);
        assertThat(statusOf(FAKE, "a")).isEqualTo("EXPIRED");
    }

    @Test
    void expiryOnlyTouchesTheJobsOfTheSourceThatRan() {
        addTarget(FAKE, "acme");
        addTarget(FAKE_RETRY, "other");
        fake.customPostings("acme", a());
        fakeRetry.customPostings("other", b());
        ingestion.runNow(FAKE).orElseThrow();
        ingestion.runNow(FAKE_RETRY).orElseThrow();

        fake.customPostings("acme");
        ingestion.runNow(FAKE).orElseThrow();
        ingestion.runNow(FAKE).orElseThrow();

        assertThat(statusOf(FAKE, "a")).isEqualTo("EXPIRED");
        assertThat(statusOf(FAKE_RETRY, "b")).isEqualTo("ACTIVE");
        Map<String, Object> other = jdbc.queryForMap("select missed_runs from job_sources where external_id = 'b'");
        assertThat(other).containsEntry("missed_runs", 0);
    }

    private void backdateLastSeen(String sourceCode, String externalId, int days) {
        jdbc.update("update job_sources set last_seen_at = now() - make_interval(days => ?) "
                + "where source_id = ? and external_id = ?", days, sourceId(sourceCode), externalId);
    }
}
