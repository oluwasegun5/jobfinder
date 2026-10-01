package com.jobfinder.core.feed.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.feed.internal.FeedDtos.AdjustmentCode;
import com.jobfinder.core.feed.internal.FeedDtos.AdjustmentReason;
import com.jobfinder.core.feed.internal.FeedbackAdjuster.Adjustment;
import com.jobfinder.core.jobs.JobAction;
import com.jobfinder.core.jobs.JobSignal;

/** The feedback arithmetic on its own: similarity, decay, per-action points, the caps, and the explanation. */
class FeedbackAdjusterTests {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final UUID ACME = UUID.randomUUID();
    private static final UUID GLOBEX = UUID.randomUUID();

    private final FeedProperties.Feedback config = defaults();
    private final FeedbackAdjuster adjuster = new FeedbackAdjuster(config);

    private static FeedProperties.Feedback defaults() {
        return new FeedProperties.Feedback(Duration.ofDays(90), Duration.ofDays(30), 100, 0.5, 15, 15, 5, 6, 4, 5, 30,
                15);
    }

    private static JobSignal signal(JobAction action, UUID company, String title, Duration age) {
        return new JobSignal(UUID.randomUUID(), company, title, action, NOW.minus(age));
    }

    private Adjustment adjust(UUID company, String title, JobSignal... signals) {
        return adjuster.adjust(UUID.randomUUID(), company, TitleTokens.of(title),
                adjuster.prepare(List.of(signals)), NOW);
    }

    private static AdjustmentReason reason(Adjustment a, AdjustmentCode code) {
        return a.reasons().stream().filter(r -> r.code() == code).findFirst().orElseThrow();
    }

    @Test
    void anUnrelatedJobIsNotMoved() {
        Adjustment a = adjust(GLOBEX, "Data Analyst", signal(JobAction.HIDDEN, ACME, "Java Backend Engineer", Duration.ZERO));

        assertThat(a.points()).isZero();
        assertThat(a.reasons()).isEmpty();
    }

    @Test
    void aHiddenJobFromTheSameCompanyCostsTheCompanyPoints() {
        Adjustment a = adjust(ACME, "Office Manager", signal(JobAction.HIDDEN, ACME, "Java Backend Engineer", Duration.ZERO));

        assertThat(a.points()).isCloseTo(-15, within(1e-9));
        assertThat(a.reasons()).extracting(AdjustmentReason::code).containsExactly(AdjustmentCode.HIDDEN_SAME_COMPANY);
        assertThat(reason(a, AdjustmentCode.HIDDEN_SAME_COMPANY).example()).isEqualTo("Java Backend Engineer");
    }

    @Test
    void aSimilarTitleCostsTheTitlePointsScaledBySimilarity() {
        // {java, backend, engineer} against {backend, engineer}: Jaccard 2/3, so 15 * 2/3 = 10
        Adjustment a = adjust(GLOBEX, "Backend Engineer",
                signal(JobAction.HIDDEN, ACME, "Senior Java Backend Engineer", Duration.ZERO));

        assertThat(a.points()).isCloseTo(-10, within(1e-9));
        assertThat(a.reasons()).extracting(AdjustmentReason::code).containsExactly(AdjustmentCode.HIDDEN_SIMILAR_TITLE);
    }

    @Test
    void sameCompanyAndSimilarTitleAddUp() {
        Adjustment a = adjust(ACME, "Java Backend Developer",
                signal(JobAction.HIDDEN, ACME, "Java Backend Engineer", Duration.ZERO));

        assertThat(a.points()).isCloseTo(-30, within(1e-9)); // 15 + 15 * 1.0 (developer is engineer)
        assertThat(a.reasons()).extracting(AdjustmentReason::code)
                .containsExactlyInAnyOrder(AdjustmentCode.HIDDEN_SAME_COMPANY, AdjustmentCode.HIDDEN_SIMILAR_TITLE);
    }

    @Test
    void titlesBelowTheThresholdAreNotSimilar() {
        // frontend and backend share only "engineer": 1/3
        Adjustment a = adjust(GLOBEX, "Frontend Engineer", signal(JobAction.HIDDEN, ACME, "Backend Engineer", Duration.ZERO));

        assertThat(a.points()).isZero();
    }

    @Test
    void savedAndAppliedJobsBoostTheirLookalikes() {
        Adjustment saved = adjust(GLOBEX, "Python Data Engineer",
                signal(JobAction.SAVED, ACME, "Python Data Developer", Duration.ZERO));
        Adjustment applied = adjust(ACME, "Gardener", signal(JobAction.APPLIED, ACME, "Python Data Developer", Duration.ZERO));

        assertThat(saved.points()).isCloseTo(6, within(1e-9));
        assertThat(reason(saved, AdjustmentCode.SAVED_SIMILAR_TITLE).points()).isCloseTo(6, within(1e-9));
        assertThat(applied.points()).isCloseTo(4, within(1e-9));
        assertThat(reason(applied, AdjustmentCode.APPLIED_SAME_COMPANY).count()).isEqualTo(1);
    }

    @Test
    void aSignalCountsForHalfAsMuchAfterOneHalfLife() {
        Adjustment fresh = adjust(ACME, "Office Manager", signal(JobAction.HIDDEN, ACME, "Java Engineer", Duration.ZERO));
        Adjustment old = adjust(ACME, "Office Manager", signal(JobAction.HIDDEN, ACME, "Java Engineer", Duration.ofDays(30)));
        Adjustment older = adjust(ACME, "Office Manager", signal(JobAction.HIDDEN, ACME, "Java Engineer", Duration.ofDays(60)));

        assertThat(old.points()).isCloseTo(fresh.points() / 2, within(1e-9));
        assertThat(older.points()).isCloseTo(fresh.points() / 4, within(1e-9));
    }

    @Test
    void aSignalAboutTheJobItselfNeverMovesIt() {
        UUID jobId = UUID.randomUUID();
        JobSignal self = new JobSignal(jobId, ACME, "Java Engineer", JobAction.SAVED, NOW);

        Adjustment a = adjuster.adjust(jobId, ACME, TitleTokens.of("Java Engineer"), adjuster.prepare(List.of(self)), NOW);

        assertThat(a.points()).isZero();
    }

    @Test
    void penaltiesAreCappedAndTheReasonsStillAddUpToTheAdjustment() {
        JobSignal[] hidden = new JobSignal[5];
        for (int i = 0; i < hidden.length; i++) {
            hidden[i] = signal(JobAction.HIDDEN, ACME, "Warehouse Operative " + i, Duration.ZERO);
        }

        Adjustment a = adjust(ACME, "Office Manager", hidden);

        assertThat(a.points()).isCloseTo(-30, within(1e-9)); // 5 * 15 = 75 capped at 30
        assertThat(reason(a, AdjustmentCode.HIDDEN_SAME_COMPANY).count()).isEqualTo(5);
        assertThat(reason(a, AdjustmentCode.HIDDEN_SAME_COMPANY).points()).isCloseTo(-30, within(0.01));
        assertThat(a.reasons()).extracting(AdjustmentReason::code).contains(AdjustmentCode.PENALTY_CAPPED);
    }

    @Test
    void boostsAreCappedSeparately() {
        JobSignal[] saved = new JobSignal[5];
        for (int i = 0; i < saved.length; i++) {
            saved[i] = signal(JobAction.SAVED, ACME, "Warehouse Operative " + i, Duration.ZERO);
        }

        Adjustment a = adjust(ACME, "Office Manager", saved);

        assertThat(a.points()).isCloseTo(15, within(1e-9)); // 5 * 5 = 25 capped at 15
        assertThat(a.reasons()).extracting(AdjustmentReason::code).contains(AdjustmentCode.BOOST_CAPPED);
    }

    @Test
    void aBoostAndAPenaltyNetAgainstEachOther() {
        Adjustment a = adjust(ACME, "Office Manager", signal(JobAction.HIDDEN, ACME, "Warehouse Operative", Duration.ZERO),
                signal(JobAction.SAVED, ACME, "Warehouse Lead", Duration.ZERO));

        assertThat(a.points()).isCloseTo(-10, within(1e-9)); // -15 + 5
    }

    @Test
    void theCapsCannotBeConfiguredHighEnoughToEmptyTheFeed() {
        assertThatThrownBy(() -> new FeedProperties.Feedback(Duration.ofDays(90), Duration.ofDays(30), 100, 0.5, 15, 15,
                5, 6, 4, 5, 80, 15)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-penalty");
    }

    @Test
    void titlesAreComparedByNormalizedWords() {
        assertThat(TitleTokens.of("Senior Java Developer (m/f/d)")).containsExactlyInAnyOrder("java", "engineer");
        assertThat(TitleTokens.of("C++ Programmer")).containsExactlyInAnyOrder("cpp", "engineer");
        assertThat(TitleTokens.jaccard(TitleTokens.of("Java Engineer"), TitleTokens.of("java developer"))).isEqualTo(1.0);
        assertThat(TitleTokens.jaccard(TitleTokens.of(""), TitleTokens.of("Java Engineer"))).isZero();
    }
}
