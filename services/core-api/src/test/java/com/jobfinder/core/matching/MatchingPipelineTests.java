package com.jobfinder.core.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;
import com.jobfinder.core.shared.ApiException;

/**
 * The three stages end to end against real Postgres, with ai-service stubbed: ordering, the cache and what
 * invalidates it, every fallback, and what is billed.
 */
class MatchingPipelineTests extends MatchingTestSupport {

    @Autowired
    private MatchService matching;

    @Autowired
    private AiUsageLedger ledger;

    private static Map<UUID, Integer> scores(Object... pairs) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((UUID) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    private RankedMatches rank(UUID userId) {
        return matching.rankedMatches(userId);
    }

    private static MatchResult byJob(RankedMatches ranked, UUID jobId) {
        return ranked.matches().stream().filter(m -> m.jobId().equals(jobId)).findFirst().orElseThrow();
    }

    @Test
    void theModelReordersWhatTheStageTwoBlendRecalled() {
        Seeded me = seedCandidate(0, "Backend engineer", "java", "postgresql");
        UUID near = job("Near", 5, "java", "postgresql");
        UUID mid = job("Mid", 40, "java");
        UUID far = job("Far", 80, "go");
        stubScores(me.userId(), scores(near, 40, mid, 95, far, 60));

        RankedMatches ranked = rank(me.userId());

        assertThat(ranked.matches()).extracting(MatchResult::jobId).containsExactly(mid, far, near);
        assertThat(ranked.matches()).extracting(MatchResult::status).containsOnly(MatchStatus.LLM_SCORED);
        assertThat(byJob(ranked, near).stage2Score()).isGreaterThan(byJob(ranked, mid).stage2Score());
        assertThat(byJob(ranked, mid).strengths()).isNotEmpty();
        assertThat(byJob(ranked, mid).model()).isEqualTo("test-model");
        assertThat(byJob(ranked, mid).promptVersion()).isEqualTo(PROMPT);
        assertThat(ranked.resumeVersionId()).isEqualTo(me.versionId());
        assertThat(ranked.stats().recalled()).isEqualTo(3);
        assertThat(ranked.stats().llmScored()).isEqualTo(3);
        assertThat(ranked.stats().aiRequests()).isEqualTo(1);
    }

    @Test
    void theStageTwoScoreIsTheConfiguredBlend() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        // cosine 1, half the job's skills held, posted an hour ago: 0.6 + 0.3 * 0.5 + 0.1 * ~1 = ~85
        UUID id = job("Half the skills", 0, "Java", "Go");
        stubScores(me.userId(), scores(id, 70));

        MatchResult result = rank(me.userId()).matches().get(0);

        assertThat(result.stage2Score()).isCloseTo(85.0, within(0.05));
        assertThat(result.llmScore()).isEqualTo(70);
        assertThat(result.score()).isEqualTo(70);
    }

    @Test
    void aSecondCallWithNothingChangedMakesNoRequestToAiService() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        UUID b = job("B", 20, "java");
        stubScores(me.userId(), scores(a, 80, b, 60));

        RankedMatches first = rank(me.userId());
        RankedMatches second = rank(me.userId());

        assertThat(scoreRequestCount(me.userId())).isEqualTo(1);
        assertThat(second.stats().aiRequests()).isZero();
        assertThat(second.stats().cached()).isEqualTo(2);
        assertThat(second.matches()).extracting(MatchResult::jobId, MatchResult::llmScore)
                .containsExactlyElementsOf(first.matches().stream().map(m -> tuple(m)).toList());
        assertThat(storedScores(me.userId())).isEqualTo(2);
    }

    private static org.assertj.core.groups.Tuple tuple(MatchResult m) {
        return org.assertj.core.groups.Tuple.tuple(m.jobId(), m.llmScore());
    }

    @Test
    void onDemandScoringSharesTheCacheWithTheRanking() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubScores(me.userId(), scores(a, 77));

        rank(me.userId());
        MatchResult opened = matching.matchJob(me.userId(), a);

        assertThat(opened.status()).isEqualTo(MatchStatus.LLM_SCORED);
        assertThat(opened.llmScore()).isEqualTo(77);
        assertThat(scoreRequestCount(me.userId())).isEqualTo(1);
    }

    @Test
    void aNewPrimaryResumeVersionIsScoredAgainAndTheOldScoresAreNotReused() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubScores(me.userId(), scores(a, 80));
        rank(me.userId());
        assertThat(scoreRequestCount(me.userId())).isEqualTo(1);

        UUID second = addVersion(me.resumeId(), 2, resumeJson("Staff engineer", "java", "kotlin"), 3);
        stubScores(me.userId(), scores(a, 55));
        RankedMatches again = rank(me.userId());

        assertThat(scoreRequestCount(me.userId())).isEqualTo(2);
        assertThat(again.resumeVersionId()).isEqualTo(second);
        assertThat(again.matches().get(0).llmScore()).isEqualTo(55);
        // The old version's row stays (pruned by retention, not by the edit); the new one has its own.
        assertThat(storedScores(me.userId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct resume_version_id) from match_scores where user_id = ?",
                Integer.class, me.userId())).isEqualTo(2);
    }

    @Test
    void editingTheCurrentVersionInPlaceInvalidatesItsScores() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubScores(me.userId(), scores(a, 80));
        rank(me.userId());

        String edited = resumeJson("Backend engineer", "java", "kubernetes");
        jdbc.update("update resume_versions set structured = cast(? as jsonb) where id = ?", edited, me.versionId());
        embedResume(me.versionId(), edited, 0);
        stubScores(me.userId(), scores(a, 62));
        RankedMatches again = rank(me.userId());

        assertThat(scoreRequestCount(me.userId())).isEqualTo(2);
        assertThat(again.matches().get(0).llmScore()).isEqualTo(62);
        assertThat(storedScores(me.userId())).isEqualTo(1);
    }

    @Test
    void whenAJobsContentChangesOnlyThatJobIsScoredAgain() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        UUID b = job("B", 20, "java");
        stubScores(me.userId(), scores(a, 80, b, 60));
        rank(me.userId());

        jdbc.update("update jobs set description_text = ? where id = ?", "Now the role is entirely different.", b);
        stubScores(me.userId(), scores(b, 30));
        RankedMatches again = rank(me.userId());

        assertThat(scoreRequestCount(me.userId())).isEqualTo(2);
        assertThat(askedJobs(scoreRequests(me.userId()).stream().max(
                java.util.Comparator.comparing(r -> r.getLoggedDate())).orElseThrow()))
                .containsExactly(b.toString());
        assertThat(byJob(again, a).llmScore()).isEqualTo(80);
        assertThat(byJob(again, b).llmScore()).isEqualTo(30);
        assertThat(again.stats().cached()).isEqualTo(1);
    }

    @Test
    void aUserWhoHasUsedUpTheDailyAllowanceGetsStageTwoResultsFlaggedAndNoModelCall() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        UUID b = job("B", 30, "java");
        stubScores(me.userId(), scores(a, 80, b, 60));
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), me.userId(), "parse_resume", "test", "test-model", 1, 1,
                new BigDecimal("1.00"), 1, "test/v1", "test", AiCallStatus.SUCCEEDED));

        RankedMatches ranked = rank(me.userId());

        assertThat(scoreRequestCount(me.userId())).isZero();
        assertThat(ranked.stats().capped()).isTrue();
        assertThat(ranked.stats().notScored()).isEqualTo(2);
        assertThat(ranked.matches()).extracting(MatchResult::status).containsOnly(MatchStatus.NOT_LLM_SCORED);
        assertThat(ranked.matches()).extracting(MatchResult::reason).containsOnly(FallbackReason.DAILY_CAP_REACHED);
        assertThat(ranked.matches()).allSatisfy(m -> {
            assertThat(m.llmScore()).isNull();
            assertThat(m.score()).isEqualTo((int) Math.round(m.stage2Score()));
        });
        assertThat(ranked.matches()).extracting(MatchResult::jobId).containsExactly(a, b);
        assertThat(storedScores(me.userId())).isZero();
    }

    @Test
    void aCachedScoreIsStillServedWhenTheAllowanceIsUsedUp() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubScores(me.userId(), scores(a, 80));
        rank(me.userId());
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), me.userId(), "parse_resume", "test", "test-model", 1, 1,
                new BigDecimal("1.00"), 1, "test/v1", "test", AiCallStatus.SUCCEEDED));

        MatchResult result = rank(me.userId()).matches().get(0);

        assertThat(result.status()).isEqualTo(MatchStatus.LLM_SCORED);
        assertThat(result.llmScore()).isEqualTo(80);
    }

    @Test
    void aJobTheModelFailedOnIsServedAsUnrankedWithItsStageTwoScoreAndIsNotCached() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID good = job("Good", 5, "java");
        UUID bad = job("Bad", 10, "java");
        stubScoresWithFailures(me.userId(), scores(good, 70), Map.of(bad, "llm_output_invalid"));

        RankedMatches ranked = rank(me.userId());

        assertThat(byJob(ranked, good).status()).isEqualTo(MatchStatus.LLM_SCORED);
        MatchResult unranked = byJob(ranked, bad);
        assertThat(unranked.status()).isEqualTo(MatchStatus.UNRANKED);
        assertThat(unranked.reason()).isEqualTo(FallbackReason.LLM_FAILED);
        assertThat(unranked.llmScore()).isNull();
        assertThat(unranked.score()).isEqualTo((int) Math.round(unranked.stage2Score()));
        assertThat(ranked.stats().unranked()).isEqualTo(1);
        assertThat(ranked.matches().get(0).jobId()).isEqualTo(good);
        assertThat(storedScores(me.userId())).isEqualTo(1);

        // The failure is not remembered: the next run asks about that job again.
        stubScores(me.userId(), scores(bad, 66));
        RankedMatches retry = rank(me.userId());
        assertThat(byJob(retry, bad).llmScore()).isEqualTo(66);
        assertThat(scoreRequestCount(me.userId())).isEqualTo(2);
    }

    @Test
    void aScoreOutsideZeroToOneHundredIsTreatedAsAFailureForThatJob() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubScores(me.userId(), scores(a, 140));

        MatchResult result = rank(me.userId()).matches().get(0);

        assertThat(result.status()).isEqualTo(MatchStatus.UNRANKED);
        assertThat(storedScores(me.userId())).isZero();
    }

    @Test
    void aJobTheModelDidNotAnswerForIsUnranked() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        UUID missing = job("Missing", 10, "java");
        stubScores(me.userId(), scores(a, 70));

        RankedMatches ranked = rank(me.userId());

        assertThat(byJob(ranked, missing).status()).isEqualTo(MatchStatus.UNRANKED);
        assertThat(byJob(ranked, a).status()).isEqualTo(MatchStatus.LLM_SCORED);
    }

    @Test
    void whenAiServiceFailsTheResultsAreStageTwoScoresFlaggedUnavailableNotAnError() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubStatus(me.userId(), 502, "{\"title\":\"Bad gateway\",\"status\":502,\"code\":\"llm_unavailable\","
                + "\"retryable\":true,\"usage\":[{\"feature\":\"match_scoring\",\"provider\":\"test\","
                + "\"model\":\"test-model\",\"input_tokens\":50,\"output_tokens\":0,\"cost_usd\":\"0.0001\","
                + "\"latency_ms\":5,\"prompt_version\":\"match_scoring/v1\",\"call_id\":\""
                + "11111111-1111-1111-1111-" + me.userId().toString().substring(24) + "\"}]}");

        RankedMatches ranked = rank(me.userId());

        MatchResult result = ranked.matches().get(0);
        assertThat(result.status()).isEqualTo(MatchStatus.NOT_LLM_SCORED);
        assertThat(result.reason()).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
        assertThat(result.jobId()).isEqualTo(a);
        assertThat(storedScores(me.userId())).isZero();
        // The failed call still cost something and is billed as failed.
        assertThat(jdbc.queryForObject("select count(*) from ai_calls where user_id = ? and feature = "
                + "'match_scoring' and status = 'FAILED'", Integer.class, me.userId())).isEqualTo(1);
    }

    @Test
    void anAnswerThatIsNotJsonIsTreatedAsUnavailable() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubStatus(me.userId(), 200, "this is not json");

        MatchResult result = rank(me.userId()).matches().get(0);

        assertThat(result.status()).isEqualTo(MatchStatus.NOT_LLM_SCORED);
        assertThat(result.reason()).isEqualTo(FallbackReason.LLM_UNAVAILABLE);
        assertThat(result.jobId()).isEqualTo(a);
    }

    @Test
    void anAnswerForAnotherPromptVersionIsNeverStoredUnderThisOne() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubScores(me.userId(), scores(a, 80));
        String body = scoresBody(me.userId(), scores(a, 80), Map.of(), "0.002").replace(
                "\"prompt_version\":\"match_scoring/v1\",\"model\"", "\"prompt_version\":\"match_scoring/v9\",\"model\"");
        stubStatus(me.userId(), 200, body);

        MatchResult result = rank(me.userId()).matches().get(0);

        assertThat(result.status()).isEqualTo(MatchStatus.NOT_LLM_SCORED);
        assertThat(storedScores(me.userId())).isZero();
    }

    @Test
    void modelUsageIsRecordedAndChargedUnderMatchScoringAndOnlyOnce() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubScores(me.userId(), scores(a, 80), "0.004");

        rank(me.userId());
        rank(me.userId());

        assertThat(jdbc.queryForObject("select count(*) from ai_calls where user_id = ? and feature = "
                + "'match_scoring' and status = 'SUCCEEDED'", Integer.class, me.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select coalesce(sum(cost_micro_usd), 0) from ai_calls where user_id = ? "
                + "and feature = 'match_scoring'", BigDecimal.class, me.userId())).isEqualByComparingTo("4000");
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id = ?", Integer.class,
                me.userId())).isEqualTo(1);
    }

    @Test
    void jobsAreSentInBatchesOfTheConfiguredSize() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        Map<UUID, Integer> all = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            all.put(job("Job " + i, i + 1, "java"), 50 + i);
        }
        stubScores(me.userId(), all);

        RankedMatches ranked = rank(me.userId());

        assertThat(ranked.matches()).hasSize(12);
        assertThat(scoreRequests(me.userId())).extracting(r -> askedJobs(r).size()).containsExactlyInAnyOrder(10, 2);
        assertThat(ranked.stats().aiRequests()).isEqualTo(2);
    }

    @Test
    void onlyTheTopOfTheStageTwoOrderIsReRanked() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        Map<UUID, Integer> all = new LinkedHashMap<>();
        for (int i = 0; i < 32; i++) {
            all.put(job("Job " + i, i * 2, "java"), 50);
        }
        stubScores(me.userId(), all);

        RankedMatches ranked = rank(me.userId());

        assertThat(ranked.stats().recalled()).isEqualTo(32);
        assertThat(ranked.stats().considered()).isEqualTo(30);
        assertThat(ranked.matches()).hasSize(30);
        assertThat(storedScores(me.userId())).isEqualTo(30);
    }

    @Test
    void anExpiredJobIsServedWithItsStageTwoScoreAndNeverSentToTheModel() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID expired = insert(spec().title("Gone").skills("java").embedding(unit(0)).status("EXPIRED"));
        stubScores(me.userId(), scores(expired, 90));

        MatchResult result = matching.matchJob(me.userId(), expired);

        assertThat(result.status()).isEqualTo(MatchStatus.NOT_LLM_SCORED);
        assertThat(result.reason()).isEqualTo(FallbackReason.JOB_EXPIRED);
        assertThat(scoreRequestCount(me.userId())).isZero();
    }

    @Test
    void anOpenedJobIsScoredEvenWhenThePreferencesWouldHaveFilteredItOut() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        savePreferences(me.userId(), "REMOTE", "", null, null, "");
        UUID onsite = insert(spec().title("Onsite").workMode("ONSITE").skills("java").embedding(unit(0)));
        stubScores(me.userId(), scores(onsite, 64));

        assertThat(rank(me.userId()).matches()).isEmpty();
        MatchResult opened = matching.matchJob(me.userId(), onsite);

        assertThat(opened.status()).isEqualTo(MatchStatus.LLM_SCORED);
        assertThat(opened.llmScore()).isEqualTo(64);
    }

    @Test
    void aJobWithoutAnEmbeddingCanStillBeOpenedAndScored() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID bare = insert(spec().title("Bare").skills("java"));
        stubScores(me.userId(), scores(bare, 58));

        MatchResult opened = matching.matchJob(me.userId(), bare);

        assertThat(opened.status()).isEqualTo(MatchStatus.LLM_SCORED);
        assertThat(opened.stage2Score()).isBetween(0.0, 100.0);
    }

    @Test
    void errorsAreTyped() {
        UUID nobody = newUser();
        Seeded me = seedCandidate(0, "Backend engineer", "java");

        assertThatThrownBy(() -> matching.matchJob(nobody, UUID.randomUUID())).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.code()).isEqualTo("resume_required"));
        assertThatThrownBy(() -> rank(nobody)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("resume_required"));
        assertThatThrownBy(() -> matching.matchJob(me.userId(), UUID.randomUUID())).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.code()).isEqualTo("job_not_found"));
    }

    @Test
    void aResumeWhoseEmbeddingIsStaleCannotBeRankedYet() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        jdbc.update("update resume_versions set embedding_input_hash = 'stale' where id = ?", me.versionId());

        assertThatThrownBy(() -> rank(me.userId())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("resume_embedding_pending"));
    }

    @Test
    void theContactBlockNeverReachesAiService() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        stubScores(me.userId(), scores(a, 80));

        rank(me.userId());

        String sent = scoreRequests(me.userId()).get(0).getBodyAsString();
        assertThat(sent).doesNotContain("test.person@example.test").doesNotContain("+1 555 0100")
                .doesNotContain("Test Person");
        assertThat(List.of(sent)).allSatisfy(s -> assertThat(s).contains("\"user_id\"").contains(PROMPT));
    }
}
