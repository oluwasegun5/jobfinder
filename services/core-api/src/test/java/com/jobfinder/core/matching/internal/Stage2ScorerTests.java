package com.jobfinder.core.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.matching.internal.MatchingProperties.Batch;
import com.jobfinder.core.matching.internal.MatchingProperties.Llm;
import com.jobfinder.core.matching.internal.MatchingProperties.Retention;
import com.jobfinder.core.matching.internal.MatchingProperties.Weights;
import com.jobfinder.core.matching.internal.Stage2Scorer.Stage2;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The stage-2 blend, with weights taken from configuration: pure arithmetic, no Spring. */
class Stage2ScorerTests {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private static MatchingProperties properties(Weights weights, Duration halfLife) {
        return new MatchingProperties("match_scoring/v1", 300, 30, weights, halfLife, 1,
                new Llm(10, 3000, Duration.ofSeconds(2), Duration.ofSeconds(120)),
                new Retention(Duration.ofDays(14), Duration.ofDays(90)),
                new Batch(true, "0 30 2 * * *", "UTC", Duration.ofDays(14), 500, 3000, 100, Duration.ofHours(2)));
    }

    private static Stage2Scorer scorer(double vector, double skills, double recency) {
        return new Stage2Scorer(properties(new Weights(vector, skills, recency), Duration.ofDays(21)));
    }

    private static Set<String> skills(String... names) {
        return Set.of(names);
    }

    @Test
    void theBlendAgreesWithTheGoldenCasesTheEvalScriptIsCheckedAgainst() throws Exception {
        JsonNode golden = JsonMapper.builder().build()
                .readTree(Files.readString(Path.of("src/test/resources/matching/stage2-golden.json")));
        Stage2Scorer scorer = scorer(golden.get("weights").get("vector").asDouble(),
                golden.get("weights").get("skills").asDouble(), golden.get("weights").get("recency").asDouble());
        for (JsonNode c : golden.get("cases")) {
            Set<String> resume = new java.util.HashSet<>();
            c.get("resume_skills").forEach(n -> resume.add(Stage2Scorer.normalize(n.asString())));
            List<String> job = new java.util.ArrayList<>();
            c.get("job_skills").forEach(n -> job.add(n.asString()));
            Double cosine = c.get("cosine").isNull() ? null : c.get("cosine").asDouble();
            Instant posted = NOW.minus(Duration.ofDays(c.get("age_days").asLong()));

            double score = scorer.score(cosine, resume, job, posted, NOW).score();

            assertThat(score).as(c.get("name").asString()).isCloseTo(c.get("expected").asDouble(), within(1e-9));
        }
    }

    @Test
    void aPerfectJobPostedNowScoresOneHundred() {
        Stage2 s = scorer(0.6, 0.3, 0.1).score(1.0, skills("java"), List.of("Java"), NOW, NOW);

        assertThat(s.score()).isCloseTo(100.0, within(1e-9));
        assertThat(s.vector()).isEqualTo(1.0);
        assertThat(s.skills()).isEqualTo(1.0);
        assertThat(s.recency()).isEqualTo(1.0);
    }

    @Test
    void theBlendIsTheWeightedMeanOfTheComponentsOnAHundredScale() {
        // cosine 0.8, half the job's skills held, posted one half-life ago: 0.6*0.8 + 0.3*0.5 + 0.1*0.5 = 0.68
        Stage2 s = scorer(0.6, 0.3, 0.1).score(0.8, skills("java"), List.of("Java", "Go"),
                NOW.minus(Duration.ofDays(21)), NOW);

        assertThat(s.score()).isCloseTo(68.0, within(1e-9));
    }

    @Test
    void theWeightsComeFromConfigurationNotFromTheCode() {
        Set<String> mine = skills("java");
        List<String> wanted = List.of("Java", "Go");
        Instant old = NOW.minus(Duration.ofDays(42));

        double vectorOnly = scorer(1, 0, 0).score(0.8, mine, wanted, old, NOW).score();
        double skillsOnly = scorer(0, 1, 0).score(0.8, mine, wanted, old, NOW).score();
        double recencyOnly = scorer(0, 0, 1).score(0.8, mine, wanted, old, NOW).score();

        assertThat(vectorOnly).isCloseTo(80.0, within(1e-9));
        assertThat(skillsOnly).isCloseTo(50.0, within(1e-9));
        assertThat(recencyOnly).isCloseTo(25.0, within(1e-9));
    }

    @Test
    void aNegativeSimilarityCountsAsNoSimilarityAndNeverPullsTheScoreBelowTheOtherComponents() {
        Stage2 s = scorer(0.6, 0.3, 0.1).score(-0.7, skills("java"), List.of("java"), NOW, NOW);

        assertThat(s.vector()).isEqualTo(0.0);
        assertThat(s.cosine()).isEqualTo(-0.7);
        assertThat(s.score()).isCloseTo(40.0, within(1e-9));
    }

    @Test
    void anUnknownComponentLeavesTheBlendAndTheOthersAreRescaled() {
        Stage2Scorer scorer = scorer(0.6, 0.3, 0.1);

        // No embedding: skills 1.0 and recency 1.0 share 0.3 + 0.1 of weight, so a perfect remainder is 100.
        Stage2 noVector = scorer.score(null, skills("java"), List.of("java"), NOW, NOW);
        // No skills listed on the job: vector 0.5 and recency 1.0 -> (0.3 + 0.1) / 0.7.
        Stage2 noSkills = scorer.score(0.5, skills("java"), List.of(), NOW, NOW);

        assertThat(noVector.vector()).isNull();
        assertThat(noVector.score()).isCloseTo(100.0, within(1e-9));
        assertThat(noSkills.skills()).isNull();
        assertThat(noSkills.score()).isCloseTo(100.0 * (0.6 * 0.5 + 0.1) / 0.7, within(1e-9));
    }

    @Test
    void whenNothingButRecencyIsKnownAndItHasNoWeightTheScoreIsZero() {
        Stage2 s = scorer(0.5, 0.5, 0).score(null, Set.of(), List.of(), NOW, NOW);

        assertThat(s.score()).isEqualTo(0.0);
    }

    @Test
    void skillOverlapIgnoresCaseAndSpacingAndCountsEachWantedSkillOnce() {
        Set<String> mine = Set.of(Stage2Scorer.normalize("  Spring   Boot "), Stage2Scorer.normalize("PostgreSQL"));

        Double overlap = Stage2Scorer.overlap(mine, List.of("spring boot", "SPRING BOOT", "postgresql", "Kafka", ""));

        assertThat(overlap).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void overlapIsUnknownWhenEitherSideListsNothing() {
        assertThat(Stage2Scorer.overlap(Set.of(), List.of("java"))).isNull();
        assertThat(Stage2Scorer.overlap(Set.of("java"), List.of())).isNull();
        assertThat(Stage2Scorer.overlap(Set.of("java"), List.of(" ", ""))).isNull();
    }

    @Test
    void recencyHalvesEveryHalfLifeAndAJobFromTheFutureIsBrandNew() {
        Stage2Scorer scorer = scorer(0.6, 0.3, 0.1);

        assertThat(scorer.recency(NOW, NOW)).isEqualTo(1.0);
        assertThat(scorer.recency(NOW.minus(Duration.ofDays(21)), NOW)).isCloseTo(0.5, within(1e-12));
        assertThat(scorer.recency(NOW.minus(Duration.ofDays(63)), NOW)).isCloseTo(0.125, within(1e-12));
        assertThat(scorer.recency(NOW.plus(Duration.ofDays(3)), NOW)).isEqualTo(1.0);
        assertThat(scorer.recency(null, NOW)).isEqualTo(0.0);
    }

    @Test
    void theHalfLifeIsConfigurable() {
        Stage2Scorer week = new Stage2Scorer(properties(new Weights(0.6, 0.3, 0.1), Duration.ofDays(7)));

        assertThat(week.recency(NOW.minus(Duration.ofDays(7)), NOW)).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void theSameInputsAlwaysGiveTheSameScore() {
        Stage2Scorer scorer = scorer(0.6, 0.3, 0.1);
        double first = scorer.score(0.42, skills("a", "b"), List.of("a", "c"), NOW.minus(Duration.ofHours(5)), NOW)
                .score();

        for (int i = 0; i < 5; i++) {
            assertThat(scorer.score(0.42, skills("a", "b"), List.of("a", "c"), NOW.minus(Duration.ofHours(5)), NOW)
                    .score()).isEqualTo(first);
        }
    }

    @Test
    void weightsThatDoNotAddUpToOneOrLeaveTheUnitIntervalAreRejected() {
        assertThatThrownBy(() -> new Weights(0.5, 0.3, 0.1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add up to 1");
        assertThatThrownBy(() -> new Weights(1.2, -0.1, -0.1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
    }

    @Test
    void propertiesRejectABadPromptVersionAndAnImpossibleRerankWindow() {
        Weights w = new Weights(0.6, 0.3, 0.1);
        Duration d = Duration.ofDays(21);
        Llm llm = new Llm(10, 3000, Duration.ofSeconds(2), Duration.ofSeconds(120));
        Retention retention = new Retention(Duration.ofDays(14), Duration.ofDays(90));
        Batch batch = new Batch(true, "0 30 2 * * *", "UTC", Duration.ofDays(14), 500, 3000, 100, Duration.ofHours(2));

        assertThatThrownBy(() -> new MatchingProperties("v1", 300, 30, w, d, 1, llm, retention, batch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prompt-version");
        assertThatThrownBy(() -> new MatchingProperties("match_scoring/v1", 20, 30, w, d, 1, llm, retention, batch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rerank-top");
        assertThatThrownBy(() -> new Retention(Duration.ofDays(30), Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
