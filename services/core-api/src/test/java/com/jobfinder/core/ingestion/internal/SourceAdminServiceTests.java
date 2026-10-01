package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.ingestion.IngestionRunPage;
import com.jobfinder.core.ingestion.IngestionRunStatus;
import com.jobfinder.core.ingestion.IngestionTestSupport;
import com.jobfinder.core.ingestion.SourceAdminService;
import com.jobfinder.core.ingestion.SourceAdminService.RunStart;
import com.jobfinder.core.ingestion.SourceOverview;
import com.jobfinder.core.ingestion.SourceSchedule;
import com.jobfinder.core.ingestion.SourceUnavailableException;
import com.jobfinder.core.ingestion.UnknownSourceException;

/**
 * What the dashboard can do to a source (ADR 0024): switch it on and off for the scheduler, start a run in the
 * background, read back its state and its history. The scheduler is driven by hand with {@code tick(now)}.
 */
class SourceAdminServiceTests extends IngestionTestSupport {

    @Autowired
    private SourceAdminService admin;

    @Autowired
    private IngestionScheduler scheduler;

    @BeforeEach
    void onlyTheFakeSourceIsInPlay() {
        // Every other source is switched off, so a tick (even one far in the future) can only ever start FAKE and
        // never reaches a real job board.
        jdbc.update("update sources set enabled = false where code <> 'FAKE'");
        addTarget(FAKE, "acme");
        fake.postings("acme", 2);
    }

    @AfterEach
    void switchTheOtherSourcesBackOn() {
        jdbc.update("update sources set enabled = true where code <> 'FAKE'");
    }

    private SourceOverview overview(String code) {
        return admin.sources().stream().filter(source -> source.code().equals(code)).findFirst().orElseThrow();
    }

    private static void awaitAll(List<Future<?>> runs) throws Exception {
        for (Future<?> run : runs) {
            run.get(30, TimeUnit.SECONDS);
        }
    }

    private void awaitRunsFinished(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).until(() -> jdbc
                .queryForObject("select count(*) from ingestion_runs where source_id = ? and status <> 'RUNNING'",
                        Integer.class, sourceId(FAKE)) >= count);
        // The lock is released just after the run row is closed.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "select count(*) from shedlock where name = 'ingestion:FAKE' and lock_until > timezone('utc', now())",
                Integer.class) == 0);
    }

    // ---- disabling stops the schedule

    @Test
    void disablingASourceStopsItsScheduleAndEnablingItResumesIt() throws Exception {
        SourceOverview off = admin.setEnabled(FAKE, false);

        assertThat(off.enabled()).isFalse();
        assertThat(off.schedule()).isEqualTo(SourceSchedule.DISABLED);
        assertThat(off.nextDueAt()).as("a disabled source has no next run").isNull();
        assertThat(jdbc.queryForObject("select enabled from sources where code = 'FAKE'", Boolean.class)).isFalse();

        List<Future<?>> started = scheduler.tick(Instant.now());
        assertThat(started).as("the scheduler tick does not start it").isEmpty();
        assertThat(runCount(FAKE)).isZero();
        assertThat(fake.calls()).isEmpty();

        // Even long after it would have been due.
        assertThat(scheduler.tick(Instant.now().plus(Duration.ofDays(2)))).isEmpty();

        SourceOverview on = admin.setEnabled(FAKE, true);
        assertThat(on.enabled()).isTrue();
        assertThat(on.schedule()).isEqualTo(SourceSchedule.SCHEDULED);
        awaitAll(scheduler.tick(Instant.now()));
        assertThat(runCount(FAKE)).as("enabled again, it runs on the next tick").isEqualTo(1);
    }

    @Test
    void aRunInFlightWhenTheSourceIsDisabledFinishesAndNothingFollowsOnTheSchedule() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fake.blockUntilReleased("acme", entered, release);

        assertThat(admin.startRun(FAKE)).isEqualTo(RunStart.STARTED);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(overview(FAKE).running()).isTrue();

        admin.setEnabled(FAKE, false);
        release.countDown();
        awaitRunsFinished(1);

        assertThat(jdbc.queryForObject("select status from ingestion_runs where source_id = ?", String.class,
                sourceId(FAKE))).as("the run was not interrupted").isEqualTo("SUCCEEDED");
        assertThat(overview(FAKE).running()).isFalse();
        assertThat(scheduler.tick(Instant.now().plus(Duration.ofDays(1)))).as("and it is not scheduled again")
                .isEmpty();
        assertThat(runCount(FAKE)).isEqualTo(1);
    }

    @Test
    void aDisabledSourceCanStillBeRunByHand() {
        admin.setEnabled(FAKE, false);

        assertThat(admin.startRun(FAKE)).isEqualTo(RunStart.STARTED);
        awaitRunsFinished(1);

        assertThat(rawPostingCount(FAKE)).isEqualTo(2);
        assertThat(overview(FAKE).enabled()).isFalse();
    }

    // ---- starting a run

    @Test
    void startingARunReturnsAtOnceAndAnotherWhileItRunsIsTurnedAway() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fake.blockUntilReleased("acme", entered, release);

        long begun = System.nanoTime();
        assertThat(admin.startRun(FAKE)).isEqualTo(RunStart.STARTED);
        assertThat(Duration.ofNanos(System.nanoTime() - begun)).as("it does not wait for the run")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(admin.startRun(FAKE)).as("the first run holds the lock").isEqualTo(RunStart.ALREADY_RUNNING);
        assertThat(ingestion.runNow(FAKE)).as("so does a blocking run").isEmpty();
        assertThat(runCount(FAKE)).as("and neither left a row").isEqualTo(1);

        release.countDown();
        awaitRunsFinished(1);
        assertThat(admin.startRun(FAKE)).as("once it is done the source can run again").isEqualTo(RunStart.STARTED);
        awaitRunsFinished(2);
    }

    @Test
    void anUnknownOrUnavailableSourceIsRefusedBeforeAnythingStarts() {
        assertThatThrownBy(() -> admin.startRun("NOPE")).isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> admin.setEnabled("NOPE", false)).isInstanceOf(UnknownSourceException.class);
        // Adzuna is registered but has no key in the test configuration (and is switched on for this check).
        jdbc.update("update sources set enabled = true where code = 'ADZUNA'");
        assertThatThrownBy(() -> admin.startRun("ADZUNA")).isInstanceOf(SourceUnavailableException.class);
        SourceOverview adzuna = overview("ADZUNA");
        assertThat(adzuna.schedule()).isEqualTo(SourceSchedule.UNAVAILABLE);
        assertThat(adzuna.unavailableReason()).isNotBlank().doesNotContainIgnoringCase("secret");
        assertThat(adzuna.nextDueAt()).isNull();
    }

    // ---- what the list says

    @Test
    void theOverviewReportsHealthTheLastRunTargetsAndWhenTheSourceIsNextDue() throws Exception {
        addTarget(FAKE, "retired", false);
        SourceOverview fresh = overview(FAKE);
        assertThat(fresh.lastRun()).isNull();
        assertThat(fresh.health()).isEqualTo("UNKNOWN");
        assertThat(fresh.enabledTargets()).isEqualTo(1);
        assertThat(fresh.totalTargets()).isEqualTo(2);

        IngestionRunStatus status = ingestion.runNow(FAKE).orElseThrow().status();

        SourceOverview ran = overview(FAKE);
        assertThat(status).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(ran.health()).isEqualTo("HEALTHY");
        assertThat(ran.lastRunAt()).isNotNull();
        assertThat(ran.lastRun().status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(ran.lastRun().targets()).isEqualTo(1);
        assertThat(ran.lastRun().fetched()).isEqualTo(2);
        assertThat(ran.lastRun().sourceCode()).isEqualTo(FAKE);
        assertThat(ran.nextDueAt()).isAfter(ran.lastRunAt().plus(Duration.ofHours(6)))
                .isBeforeOrEqualTo(ran.lastRunAt().plus(Duration.ofHours(6)).plus(Duration.ofMinutes(15)));
        assertThat(ran.alerts()).isEmpty();
        assertThat(ran.running()).isFalse();
    }

    @Test
    void theOverviewListsTheAlertsThatAreOpen() {
        fake.postings("acme", 10);
        ingestion.runNow(FAKE);
        fake.postings("acme", 0);
        ingestion.runNow(FAKE);

        SourceOverview source = overview(FAKE);

        assertThat(source.alerts()).hasSize(1);
        assertThat(source.alerts().get(0).rule()).isEqualTo("ZERO_JOBS");
        assertThat(source.alerts().get(0).since()).isNotNull();
        assertThat(source.alerts().get(0).lastNotifiedAt()).isNotNull();
        assertThat(source.alerts().get(0).detail()).contains("fetched 0 postings");

        fake.postings("acme", 10);
        ingestion.runNow(FAKE);
        assertThat(overview(FAKE).alerts()).as("cleared once the source recovers").isEmpty();
    }

    // ---- the history

    @Test
    void theRunHistoryIsPagedNewestFirstAndCanBeFilteredBySource() {
        jdbc.update("update sources set enabled = true where code = 'FAKE_AGG'");
        addTarget(FAKE_AGG, "search");
        fakeAgg.postings("search", 1);
        for (int i = 0; i < 3; i++) {
            ingestion.runNow(FAKE);
        }
        ingestion.runNow(FAKE_AGG);

        IngestionRunPage first = admin.runs(FAKE, 0, 2);
        IngestionRunPage second = admin.runs(FAKE, 1, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.items()).allSatisfy(run -> assertThat(run.sourceCode()).isEqualTo(FAKE));
        assertThat(first.items().get(0).startedAt()).isAfterOrEqualTo(first.items().get(1).startedAt());
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).startedAt()).isBeforeOrEqualTo(first.items().get(1).startedAt());
        assertThat(first.items().get(0).fetched()).isEqualTo(2);
        assertThat(first.items().get(0).targets()).isEqualTo(1);
        assertThat(admin.runs(FAKE, 2, 2).items()).as("past the end").isEmpty();

        IngestionRunPage all = admin.runs(null, 0, 100);
        assertThat(all.items()).extracting(run -> run.sourceCode()).contains(FAKE, FAKE_AGG);
        assertThat(all.totalElements()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void theHistoryShowsWhyARunFailed() {
        addTarget(FAKE, "gone");
        fake.failPermanently("gone");
        ingestion.runNow(FAKE);

        var run = admin.runs(FAKE, 0, 5).items().get(0);

        assertThat(run.status()).isEqualTo(IngestionRunStatus.PARTIAL);
        assertThat(run.errors()).isEqualTo(1);
        assertThat(run.errorSummary()).contains("gone");
        assertThat(run.finishedAt()).isNotNull();
    }

    @Test
    void badPagingOrAnUnknownSourceIsRejected() {
        assertThatThrownBy(() -> admin.runs(null, -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.runs(null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.runs(null, 0, SourceAdminService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.runs("NOPE", 0, 10)).isInstanceOf(UnknownSourceException.class);
    }
}
