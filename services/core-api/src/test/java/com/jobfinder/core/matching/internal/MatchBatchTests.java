package com.jobfinder.core.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;
import com.jobfinder.core.identity.UserActivity;
import com.jobfinder.core.matching.MatchingTestSupport;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * The nightly run: who counts as active, what is skipped, the per-run user and request budgets, a user that blows up,
 * the run record, pruning, and the lock.
 */
class MatchBatchTests extends MatchingTestSupport {

    @Autowired
    private AiUsageLedger ledger;

    @Autowired
    private UserActivity activity;

    @Autowired
    private MatchingPipeline pipeline;

    @Autowired
    private MatchBatchService batch;

    @Autowired
    private MatchScoreStore scores;

    @Autowired
    private MatchingProperties properties;

    @Autowired
    private JdbcClient client;

    @Autowired
    private LockingTaskExecutor lockExecutor;

    /** A run holds the lock for a minute after it ends (lock-at-least-for), so each test starts with it free. */
    @BeforeEach
    @AfterEach
    void releaseTheLock() {
        // Expire it rather than delete it: ShedLock remembers a row exists and would only ever update it.
        jdbc.update("update shedlock set lock_until = now() - interval '1 hour' where name = ?",
                MatchBatchScheduler.LOCK_NAME);
    }

    private Seeded onboarded(String headline) {
        Seeded me = seedCandidate(0, headline, "java");
        saveEmptyPreferences(me.userId());
        signedInDaysAgo(me.userId(), 1);
        return me;
    }

    private MatchBatchService with(UserActivity users, MatchingPipeline pipe, int maxUsers, int maxRequests) {
        MatchingProperties.Batch b = properties.batch();
        MatchingProperties p = new MatchingProperties(properties.promptVersion(), properties.recallLimit(),
                properties.rerankTop(), properties.weights(), properties.recencyHalfLife(), properties.seniorityBand(),
                properties.llm(), properties.retention(), new MatchingProperties.Batch(true, b.cron(), b.zone(),
                        b.activeWithin(), maxUsers, maxRequests, b.userPageSize(), b.lockAtMostFor()));
        return new MatchBatchService(users, pipe, scores, p, client, new SimpleMeterRegistry());
    }

    /** Postgres orders uuids as unsigned bytes, which is not {@code UUID.compareTo}; the fake must agree. */
    private static int unsigned(UUID x, UUID y) {
        int high = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
    }

    private static UserActivity fixed(UUID... ids) {
        List<UUID> sorted = java.util.Arrays.stream(ids).sorted(MatchBatchTests::unsigned).toList();
        return (since, after, limit) -> sorted.stream().filter(id -> unsigned(id, after) > 0).limit(limit).toList();
    }

    @Test
    void onlyActiveUsersAreMatchedAndInactiveDisabledAndDeletedOnesAreLeftAlone() {
        UUID job = job("Job", 5, "java");
        Seeded active = onboarded("Active");
        Seeded stale = seedCandidate(0, "Stale", "java");
        saveEmptyPreferences(stale.userId());
        signedInDaysAgo(stale.userId(), 30);
        Seeded neverSignedIn = seedCandidate(0, "Never", "java");
        saveEmptyPreferences(neverSignedIn.userId());
        Seeded disabled = onboarded("Disabled");
        jdbc.update("update users set status = 'DISABLED' where id = ?", disabled.userId());
        Seeded deleted = onboarded("Deleted");
        jdbc.update("update users set deleted_at = now() where id = ?", deleted.userId());
        for (Seeded s : List.of(active, stale, neverSignedIn, disabled, deleted)) {
            stubScores(s.userId(), Map.of(job, 70));
        }

        MatchBatchService.Summary summary = batch.runOnce();

        assertThat(scoreRequestCount(active.userId())).isEqualTo(1);
        assertThat(storedScores(active.userId())).isEqualTo(1);
        for (Seeded s : List.of(stale, neverSignedIn, disabled, deleted)) {
            assertThat(scoreRequestCount(s.userId())).isZero();
            assertThat(storedScores(s.userId())).isZero();
        }
        assertThat(summary.status()).isEqualTo("COMPLETED");
        assertThat(summary.matched()).isGreaterThanOrEqualTo(1);
        assertThat(summary.llmScored()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aSecondRunCostsNothingBecauseTheScoresAreCached() {
        UUID job = job("Job", 5, "java");
        Seeded me = onboarded("Me");
        stubScores(me.userId(), Map.of(job, 70));

        batch.runOnce();
        MatchBatchService.Summary second = batch.runOnce();

        assertThat(scoreRequestCount(me.userId())).isEqualTo(1);
        assertThat(second.cached()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void usersWithoutPreferencesOrAParsedResumeAreSkippedNotFailed() {
        Seeded noPreferences = seedCandidate(0, "No prefs", "java");
        UUID noResume = newUser();
        Seeded ready = onboarded("Ready");
        UUID job = job("Job", 5, "java");
        stubScores(ready.userId(), Map.of(job, 70));
        stubScores(noPreferences.userId(), Map.of(job, 70));
        MatchBatchService run = with(fixed(noPreferences.userId(), noResume, ready.userId()), pipeline, 10, 100);

        MatchBatchService.Summary summary = run.runOnce();

        assertThat(summary.considered()).isEqualTo(3);
        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(2);
        assertThat(summary.failed()).isZero();
        assertThat(scoreRequestCount(noPreferences.userId())).isZero();
    }

    @Test
    void aUserWhoseMatchingBlowsUpIsCountedAndTheRunCarriesOn() {
        UUID job = job("Job", 5, "java");
        Seeded first = onboarded("First");
        Seeded broken = onboarded("Broken");
        Seeded last = onboarded("Last");
        for (Seeded s : List.of(first, broken, last)) {
            stubScores(s.userId(), Map.of(job, 70));
        }
        MatchingPipeline flaky = spy(pipeline);
        doThrow(new IllegalStateException("boom")).when(flaky).rank(eq(broken.userId()), eq(true));
        MatchBatchService run = with(fixed(first.userId(), broken.userId(), last.userId()), flaky, 10, 100);

        MatchBatchService.Summary summary = run.runOnce();

        assertThat(summary.status()).isEqualTo("COMPLETED");
        assertThat(summary.considered()).isEqualTo(3);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.matched()).isEqualTo(2);
        assertThat(storedScores(first.userId())).isEqualTo(1);
        assertThat(storedScores(last.userId())).isEqualTo(1);
        assertThat(storedScores(broken.userId())).isZero();
    }

    @Test
    void theRunStopsAtTheUserBudgetAndSaysSo() {
        UUID job = job("Job", 5, "java");
        List<Seeded> users = List.of(onboarded("A"), onboarded("B"), onboarded("C"));
        users.forEach(s -> stubScores(s.userId(), Map.of(job, 70)));
        MatchBatchService run = with(fixed(users.stream().map(Seeded::userId).toArray(UUID[]::new)), pipeline, 2, 100);

        MatchBatchService.Summary summary = run.runOnce();

        assertThat(summary.status()).isEqualTo("STOPPED");
        assertThat(summary.stopReason()).isEqualTo("MAX_USERS");
        assertThat(summary.considered()).isEqualTo(2);
        assertThat(users.stream().filter(s -> storedScores(s.userId()) > 0)).hasSize(2);
    }

    @Test
    void theRunStopsStartingUsersOnceTheAiRequestBudgetIsSpent() {
        UUID job = job("Job", 5, "java");
        List<Seeded> users = List.of(onboarded("A"), onboarded("B"), onboarded("C"));
        users.forEach(s -> stubScores(s.userId(), Map.of(job, 70)));
        MatchBatchService run = with(fixed(users.stream().map(Seeded::userId).toArray(UUID[]::new)), pipeline, 10, 2);

        MatchBatchService.Summary summary = run.runOnce();

        assertThat(summary.status()).isEqualTo("STOPPED");
        assertThat(summary.stopReason()).isEqualTo("AI_REQUEST_BUDGET");
        assertThat(summary.aiRequests()).isEqualTo(2);
        assertThat(summary.matched()).isEqualTo(2);
        assertThat(users.stream().mapToInt(s -> scoreRequestCount(s.userId())).sum()).isEqualTo(2);
    }

    @Test
    void theRunIsRecordedInTheDatabase() {
        UUID job = job("Job", 5, "java");
        Seeded me = onboarded("Me");
        stubScores(me.userId(), Map.of(job, 70));
        MatchBatchService run = with(fixed(me.userId()), pipeline, 10, 100);

        MatchBatchService.Summary summary = run.runOnce();

        Map<String, Object> row = jdbc.queryForMap("select * from match_batch_runs where id = ?", summary.runId());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("users_considered")).isEqualTo(1);
        assertThat(row.get("users_matched")).isEqualTo(1);
        assertThat(row.get("jobs_llm_scored")).isEqualTo(1);
        assertThat(row.get("ai_requests")).isEqualTo(1);
    }

    @Test
    void aCappedUserIsMatchedWithStageTwoOnlyAndCounted() {
        UUID job = job("Job", 5, "java");
        Seeded me = onboarded("Me");
        stubScores(me.userId(), Map.of(job, 70));
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), me.userId(), "parse_resume", "test", "test-model", 1, 1,
                new BigDecimal("1.00"), 1, "test/v1", "test", AiCallStatus.SUCCEEDED));
        MatchBatchService run = with(fixed(me.userId()), pipeline, 10, 100);

        MatchBatchService.Summary summary = run.runOnce();

        assertThat(summary.capped()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(scoreRequestCount(me.userId())).isZero();
    }

    @Test
    void theEndOfARunPrunesSupersededAndOldScoresButKeepsTheCurrentOnes() {
        UUID job = job("Job", 5, "java");
        Seeded me = onboarded("Me");
        stubScores(me.userId(), Map.of(job, 70));
        batch.runOnce();
        UUID second = addVersion(me.resumeId(), 2, resumeJson("Me again", "java", "go"), 2);
        batch.runOnce();
        assertThat(storedScores(me.userId())).isEqualTo(2);
        jdbc.update("update match_scores set computed_at = now() - interval '20 days' where resume_version_id = ?",
                me.versionId());

        MatchBatchService.Summary summary = with(fixed(), pipeline, 10, 100).runOnce();

        assertThat(summary.pruned()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("select resume_version_id from match_scores where user_id = ?", UUID.class,
                me.userId())).containsExactly(second);
    }

    @Test
    void scoresOlderThanTheMaximumAgeAreDroppedEvenWithoutANewerVersion() {
        UUID job = job("Job", 5, "java");
        Seeded me = onboarded("Me");
        stubScores(me.userId(), Map.of(job, 70));
        batch.runOnce();
        jdbc.update("update match_scores set computed_at = now() - interval '100 days' where user_id = ?",
                me.userId());

        with(fixed(), pipeline, 10, 100).runOnce();

        assertThat(storedScores(me.userId())).isZero();
    }

    @Test
    void theSchedulerRunsTheBatchUnderTheLockAndReportsWhetherItRan() {
        MatchBatchService run = with(fixed(), pipeline, 10, 100);
        MatchBatchScheduler scheduler = new MatchBatchScheduler(run, lockExecutor, properties);

        assertThat(scheduler.run()).isTrue();
        long runs = jdbc.queryForObject("select count(*) from match_batch_runs", Long.class);
        assertThat(runs).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theSchedulerDoesNotRunWhileAnotherInstanceHoldsTheLock() {
        jdbc.update("""
                insert into shedlock (name, lock_until, locked_at, locked_by)
                values (?, now() + interval '1 hour', now(), 'another-instance')
                on conflict (name) do update set lock_until = excluded.lock_until, locked_by = excluded.locked_by
                """, MatchBatchScheduler.LOCK_NAME);
        try {
            MatchBatchScheduler scheduler = new MatchBatchScheduler(with(fixed(), pipeline, 10, 100), lockExecutor,
                    properties);
            long before = jdbc.queryForObject("select count(*) from match_batch_runs", Long.class);

            assertThat(scheduler.run()).isFalse();

            assertThat(jdbc.queryForObject("select count(*) from match_batch_runs", Long.class)).isEqualTo(before);
        } finally {
            releaseTheLock();
        }
    }

    @Test
    void userActivityListsRecentSignInsInIdOrderAndPagesThroughThem() {
        UUID a = newUser();
        UUID b = newUser();
        UUID c = newUser();
        for (UUID u : List.of(a, b, c)) {
            signedInDaysAgo(u, 1);
        }
        Instant since = Instant.now().minus(Duration.ofDays(2));

        List<UUID> all = new java.util.ArrayList<>();
        UUID after = new UUID(0, 0);
        while (true) {
            List<UUID> page = activity.activeSince(since, after, 2);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            after = page.get(page.size() - 1);
        }

        assertThat(all).contains(a, b, c).doesNotHaveDuplicates();
        assertThat(all).isSortedAccordingTo(MatchBatchTests::unsigned);
        assertThat(activity.activeSince(Instant.now().plusSeconds(60), new UUID(0, 0), 100))
                .doesNotContain(a, b, c);
    }

    @Test
    void theMetersCountUsersByOutcome() {
        UUID job = job("Job", 5, "java");
        Seeded me = onboarded("Me");
        stubScores(me.userId(), Map.of(job, 70));
        MeterRegistry registry = new SimpleMeterRegistry();
        MatchingProperties p = properties;
        MatchBatchService run = new MatchBatchService(fixed(me.userId(), newUser()), pipeline, scores, p, client,
                registry);

        run.runOnce();

        assertThat(registry.get("matching.batch.users").tag("outcome", "matched").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("matching.batch.users").tag("outcome", "skipped").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("matching.batch.duration").timer().count()).isEqualTo(1L);
    }
}
