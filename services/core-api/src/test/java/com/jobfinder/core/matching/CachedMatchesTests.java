package com.jobfinder.core.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.shared.ApiException;

/** {@link MatchService#cachedMatches}: the feed's read of the score cache, which never asks the model. */
class CachedMatchesTests extends MatchingTestSupport {

    @Autowired
    private MatchService matching;

    private static Map<UUID, Integer> scores(Object... pairs) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((UUID) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    @Test
    void anEmptyCacheGivesEstimatesAndMakesNoRequest() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID near = job("Near", 5, "java");
        UUID far = job("Far", 60, "java");
        stubScores(me.userId(), scores(near, 80, far, 70));

        RankedMatches cached = matching.cachedMatches(me.userId(), 100);

        assertThat(scoreRequestCount(me.userId())).isZero();
        assertThat(cached.matches()).extracting(MatchResult::jobId).containsExactly(near, far);
        assertThat(cached.matches()).allSatisfy(m -> {
            assertThat(m.status()).isEqualTo(MatchStatus.NOT_LLM_SCORED);
            assertThat(m.reason()).isNull();
            assertThat(m.llmScore()).isNull();
        });
        assertThat(cached.stats().recalled()).isEqualTo(2);
        assertThat(cached.stats().cached()).isZero();
        assertThat(storedScores(me.userId())).isZero();
    }

    @Test
    void scoredJobsComeBackModelScoredAndFirstAndTheRestAreEstimates() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID scored = job("Scored", 60, "java");
        UUID unscored = job("Unscored", 5, "java");
        stubScores(me.userId(), scores(scored, 77));
        matching.matchJob(me.userId(), scored); // the on-demand path fills the cache for this one job
        assertThat(scoreRequestCount(me.userId())).isEqualTo(1);

        RankedMatches cached = matching.cachedMatches(me.userId(), 100);

        assertThat(scoreRequestCount(me.userId())).isEqualTo(1);
        assertThat(cached.matches()).extracting(MatchResult::jobId).containsExactly(scored, unscored);
        assertThat(cached.matches().get(0).status()).isEqualTo(MatchStatus.LLM_SCORED);
        assertThat(cached.matches().get(0).score()).isEqualTo(77);
        assertThat(cached.matches().get(0).strengths()).isNotEmpty();
        assertThat(cached.matches().get(1).status()).isEqualTo(MatchStatus.NOT_LLM_SCORED);
        assertThat(cached.stats().cached()).isEqualTo(1);
        assertThat(cached.stats().notScored()).isEqualTo(1);
    }

    @Test
    void theLimitBoundsTheRecallAndHiddenJobsAreLeftOut() {
        Seeded me = seedCandidate(0, "Backend engineer", "java");
        UUID a = job("A", 5, "java");
        UUID b = job("B", 10, "java");
        UUID c = job("C", 15, "java");
        jdbc.update("insert into user_job_actions (user_id, job_id, action, created_at) values (?, ?, 'HIDDEN', now())",
                me.userId(), a);

        RankedMatches cached = matching.cachedMatches(me.userId(), 1);

        assertThat(cached.matches()).hasSize(1);
        assertThat(cached.matches().get(0).jobId()).isNotEqualTo(a).isIn(b, c);
    }

    @Test
    void withoutAResumeOrWithAStaleVectorItFailsLikeTheRanking() {
        UUID noResume = newUser();
        assertThatThrownBy(() -> matching.cachedMatches(noResume, 10)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("resume_required");

        Seeded stale = seedCandidate(0, "Backend engineer", "java");
        jdbc.update("update resume_versions set embedding_input_hash = 'stale' where id = ?", stale.versionId());
        assertThatThrownBy(() -> matching.cachedMatches(stale.userId(), 10)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("resume_embedding_pending");
    }
}
