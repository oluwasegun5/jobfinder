package com.jobfinder.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** The pipeline end to end against real Postgres, with a fake source: fetch, store raw, record the run. */
class IngestionPipelineTests extends IngestionTestSupport {

    @Test
    void aRunFetchesEveryEnabledTargetAndStoresTheRawPostings() {
        UUID acme = addTarget(FAKE, "acme");
        addTarget(FAKE, "globex");
        addTarget(FAKE, "retired", false);
        fake.postings("acme", 3).postings("globex", 2).postings("retired", 4);

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.sourceCode()).isEqualTo(FAKE);
        assertThat(summary.fetched()).isEqualTo(5);
        assertThat(summary.created()).isEqualTo(5);
        assertThat(summary.updated()).isZero();
        assertThat(summary.expired()).isZero();
        assertThat(summary.errors()).isZero();
        assertThat(fake.callsFor("retired")).as("a disabled target is never fetched").isZero();

        assertThat(rawPostingCount(FAKE)).isEqualTo(5);
        Map<String, Object> posting = jdbc.queryForMap("select payload ->> 'title' as title, target_id, fetched_at "
                + "from raw_job_postings where external_id = 'acme-1'");
        assertThat(posting.get("title")).isEqualTo("Job acme-1");
        assertThat(posting.get("target_id")).isEqualTo(acme);
        assertThat(posting.get("fetched_at")).isNotNull();
    }

    @Test
    void theRunIsRecordedWithItsCountsAndTheSourceHealthIsUpdated() {
        addTarget(FAKE, "acme");
        fake.postings("acme", 4);

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        Map<String, Object> run = jdbc.queryForMap("select * from ingestion_runs where id = ?", summary.runId());
        assertThat(run.get("status")).isEqualTo("SUCCEEDED");
        assertThat(run.get("started_at")).isNotNull();
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(run).containsEntry("fetched", 4).containsEntry("created", 4).containsEntry("updated", 0)
                .containsEntry("expired", 0).containsEntry("errors", 0);
        assertThat(run.get("error_summary")).isNull();
        assertThat(jdbc.queryForObject("select last_run_at is not null from sources where code = 'FAKE'",
                Boolean.class)).isTrue();
        assertThat(health(FAKE)).isEqualTo("HEALTHY");
    }

    @Test
    void runningAgainRefreshesTheStoredPostingsInsteadOfDuplicatingThem() {
        addTarget(FAKE, "acme");
        fake.postings("acme", 3);
        ingestion.runNow(FAKE).orElseThrow();

        fake.payloadVersion(2);
        IngestionRunSummary second = ingestion.runNow(FAKE).orElseThrow();

        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(3);
        assertThat(rawPostingCount(FAKE)).isEqualTo(3);
        assertThat(jdbc.queryForList("select distinct payload ->> 'v' from raw_job_postings", String.class))
                .containsExactly("2");
        assertThat(runCount(FAKE)).isEqualTo(2);
    }

    @Test
    void aFailingTargetDoesNotStopTheRunAndMakesItPartial() {
        addTarget(FAKE, "a-good");
        addTarget(FAKE, "b-gone");
        addTarget(FAKE, "c-good");
        fake.postings("a-good", 3).failPermanently("b-gone").postings("c-good", 2);

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.PARTIAL);
        assertThat(summary.fetched()).isEqualTo(5);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(rawPostingCount(FAKE)).isEqualTo(5);
        assertThat(fake.callsFor("b-gone")).as("a permanent failure is not retried").isEqualTo(1);
        assertThat(jdbc.queryForObject("select error_summary from ingestion_runs where id = ?", String.class,
                summary.runId())).contains("b-gone").contains("HTTP 404");
        assertThat(health(FAKE)).isEqualTo("DEGRADED");
    }

    @Test
    void aRunWhoseEveryTargetFailsIsFailed() {
        addTarget(FAKE, "one");
        addTarget(FAKE, "two");
        fake.failPermanently("one").failPermanently("two");

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(summary.errors()).isEqualTo(2);
        assertThat(summary.fetched()).isZero();
        assertThat(rawPostingCount(FAKE)).isZero();
        assertThat(health(FAKE)).isEqualTo("FAILING");
    }

    @Test
    void aSourceWithNoTargetsSucceedsWithNothingFetched() {
        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.fetched()).isZero();
        assertThat(health(FAKE)).as("nothing was proven either way").isEqualTo("UNKNOWN");
    }

    @Test
    void adaptersAreHandedTheStartOfTheLastSuccessfulRunAsSince() {
        addTarget(FAKE, "acme");
        fake.postings("acme", 1);

        ingestion.runNow(FAKE).orElseThrow();
        Instant firstStart = jdbc.queryForObject("select started_at from ingestion_runs where source_id = ?",
                java.sql.Timestamp.class, sourceId(FAKE)).toInstant();
        ingestion.runNow(FAKE).orElseThrow();
        fake.failPermanently("acme");
        assertThat(ingestion.runNow(FAKE).orElseThrow().status()).isEqualTo(IngestionRunStatus.FAILED);
        fake.postings("acme", 1);
        ingestion.runNow(FAKE).orElseThrow();

        List<Instant> since = fake.calls().stream().map(FakeJobSourceAdapter.Call::since).toList();
        assertThat(since.get(0)).as("first run: nothing to be incremental from").isNull();
        assertThat(since.get(1)).as("after one successful run").isCloseTo(firstStart,
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(since.get(3)).as("a failed run does not move it forward")
                .isEqualTo(since.get(2));
    }

    @Test
    void onlyOneRunOfASourceHappensAtATime() throws Exception {
        addTarget(FAKE, "slow");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fake.blockUntilReleased("slow", entered, release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<IngestionRunSummary>> first = executor.submit(() -> ingestion.runNow(FAKE));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(ingestion.runNow(FAKE)).as("a second run is turned away, not queued").isEmpty();
            assertThat(runCount(FAKE)).as("and nothing was started for it").isEqualTo(1);

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
            assertThat(ingestion.runNow(FAKE)).as("the lock is released when the run ends").isPresent();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void differentSourcesRunIndependentlyOfEachOther() throws Exception {
        addTarget(FAKE, "slow");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fake.blockUntilReleased("slow", entered, release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<IngestionRunSummary>> first = executor.submit(() -> ingestion.runNow(FAKE));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(ingestion.runNow(FAKE_RETRY)).isPresent();

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aRunLeftRunningByAStoppedInstanceIsClosedByTheNextRun() {
        UUID stale = UUID.randomUUID();
        jdbc.update("insert into ingestion_runs (id, source_id, started_at, status, created_at, updated_at) "
                + "values (?, ?, now() - interval '2 hours', 'RUNNING', now(), now())", stale, sourceId(FAKE));

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        Map<String, Object> closed = jdbc.queryForMap("select status, finished_at, error_summary "
                + "from ingestion_runs where id = ?", stale);
        assertThat(closed.get("status")).isEqualTo("FAILED");
        assertThat(closed.get("finished_at")).isNotNull();
        assertThat((String) closed.get("error_summary")).startsWith("Interrupted");
        assertThat(jdbc.queryForObject("select count(*) from ingestion_runs where status = 'RUNNING'",
                Integer.class)).isZero();
    }

    @Test
    void aManualRunWorksEvenWhenTheSourceIsDisabled() {
        jdbc.update("update sources set enabled = false where code = 'FAKE'");

        assertThat(ingestion.runNow(FAKE)).isPresent();
    }

    @Test
    void runningAnUnknownSourceIsRejected() {
        assertThatThrownBy(() -> ingestion.runNow("NO_SUCH_SOURCE")).isInstanceOf(UnknownSourceException.class)
                .hasMessageContaining("NO_SUCH_SOURCE");
    }

    @Test
    void everyAdapterIsRegisteredAsAnEnabledSourceOfItsKind() {
        assertThat(jdbc.queryForList("select code || ':' || kind || ':' || enabled from sources "
                + "where code like 'FAKE%' order by code", String.class))
                .containsExactly("FAKE:ATS:true", "FAKE_BREAKER:AGGREGATOR:true", "FAKE_RETRY:ATS:true");
    }
}
