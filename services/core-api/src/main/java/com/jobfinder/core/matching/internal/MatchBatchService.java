package com.jobfinder.core.matching.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.jobfinder.core.identity.UserActivity;
import com.jobfinder.core.matching.RankedMatches;
import com.jobfinder.core.shared.ApiException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The nightly run: matches every active user and fills the score cache, so that opening the feed or a job later costs
 * nothing (docs/adr/0026-matching-engine.md).
 *
 * <p>An <em>active</em> user signed in (or renewed a session) within {@code app.matching.batch.active-within}, is
 * not disabled or deleted, has a primary resume with parsed content, saved preferences and a current resume
 * embedding. Users who fail those last three are counted as skipped, not failed. A run:
 * <ul>
 * <li>takes users in id order, a page at a time, and never lets one user's error stop the others (it is counted,
 * logged without personal data, and the run moves on);</li>
 * <li>stops starting new users once it has handled {@code max-users-per-run} users or made
 * {@code max-ai-requests-per-run} requests to ai-service (the budget guard) and says so in its record;</li>
 * <li>leaves a row in {@code match_batch_runs} (V23) with the counts, and publishes Micrometer meters
 * ({@code matching.batch.users} by outcome, {@code matching.batch.duration}).</li>
 * </ul>
 * Each user's own daily AI allowance still applies: a capped user is matched with stage-2 scores only and counted as
 * capped.
 */
@Service
class MatchBatchService {

    /** The counts of a run, as recorded. */
    record Summary(UUID runId, String status, String stopReason, int considered, int matched, int skipped, int failed,
            int capped, int llmScored, int cached, int aiRequests, int pruned) {
    }

    private static final Logger log = LoggerFactory.getLogger(MatchBatchService.class);
    private static final UUID FIRST_ID = new UUID(0L, 0L);

    private final UserActivity activity;
    private final MatchingPipeline pipeline;
    private final MatchScoreStore scores;
    private final MatchingProperties properties;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    MatchBatchService(UserActivity activity, MatchingPipeline pipeline, MatchScoreStore scores,
            MatchingProperties properties, JdbcClient jdbc, MeterRegistry meters) {
        this.activity = activity;
        this.pipeline = pipeline;
        this.scores = scores;
        this.properties = properties;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    Summary runOnce() {
        MatchingProperties.Batch batch = properties.batch();
        UUID runId = UUID.randomUUID();
        jdbc.sql("insert into match_batch_runs (id, started_at, status) values (:id, now(), 'RUNNING')")
                .param("id", runId).update();
        Timer.Sample sample = Timer.start(meters);
        int considered = 0;
        int matched = 0;
        int skipped = 0;
        int failed = 0;
        int capped = 0;
        int llmScored = 0;
        int cached = 0;
        int requests = 0;
        String stopReason = null;
        String status = "COMPLETED";
        int pruned = 0;
        try {
            Instant since = Instant.now().minus(batch.activeWithin());
            UUID after = FIRST_ID;
            paging:
            while (true) {
                List<UUID> page = activity.activeSince(since, after, batch.userPageSize());
                if (page.isEmpty()) {
                    break;
                }
                for (UUID userId : page) {
                    after = userId;
                    if (considered >= batch.maxUsersPerRun()) {
                        stopReason = "MAX_USERS";
                        break paging;
                    }
                    if (requests >= batch.maxAiRequestsPerRun()) {
                        stopReason = "AI_REQUEST_BUDGET";
                        break paging;
                    }
                    considered++;
                    try {
                        RankedMatches ranked = pipeline.rank(userId, true);
                        matched++;
                        RankedMatches.Stats s = ranked.stats();
                        llmScored += s.llmScored();
                        cached += s.cached();
                        requests += s.aiRequests();
                        if (s.capped()) {
                            capped++;
                        }
                        meters.counter("matching.batch.users", "outcome", "matched").increment();
                    } catch (ApiException e) {
                        // No parsed primary resume, no preferences, or the resume's embedding is not ready yet.
                        skipped++;
                        meters.counter("matching.batch.users", "outcome", "skipped").increment();
                        log.debug("Skipped a user in the match batch: {}", e.code());
                    } catch (RuntimeException e) {
                        failed++;
                        meters.counter("matching.batch.users", "outcome", "failed").increment();
                        log.error("Matching failed for user {} in the nightly batch", userId, e);
                    }
                }
            }
            if (stopReason != null) {
                status = "STOPPED";
                log.warn("Nightly matching stopped early: {}", stopReason);
            }
        } catch (RuntimeException e) {
            status = "FAILED";
            stopReason = "ERROR";
            log.error("Nightly matching run failed", e);
        } finally {
            try {
                pruned = scores.prune(properties.retention().supersededAfter(), properties.retention().maxAge());
            } catch (RuntimeException e) {
                log.error("Pruning old match scores failed", e);
            }
            sample.stop(meters.timer("matching.batch.duration"));
            jdbc.sql("""
                    update match_batch_runs set finished_at = now(), status = :status, stop_reason = :stop,
                           users_considered = :considered, users_matched = :matched, users_skipped = :skipped,
                           users_failed = :failed, users_capped = :capped, jobs_llm_scored = :llmScored,
                           jobs_cached = :cached, ai_requests = :requests, scores_pruned = :pruned
                     where id = :id
                    """)
                    .param("status", status).param("stop", stopReason).param("considered", considered)
                    .param("matched", matched).param("skipped", skipped).param("failed", failed)
                    .param("capped", capped).param("llmScored", llmScored).param("cached", cached)
                    .param("requests", requests).param("pruned", pruned).param("id", runId).update();
        }
        log.info("Nightly matching {}: users considered={} matched={} skipped={} failed={} capped={}, jobs llmScored={} "
                + "cached={}, aiRequests={}, pruned={}", status, considered, matched, skipped, failed, capped,
                llmScored, cached, requests, pruned);
        return new Summary(runId, status, stopReason, considered, matched, skipped, failed, capped, llmScored, cached,
                requests, pruned);
    }
}
