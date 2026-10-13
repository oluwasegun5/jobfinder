package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import com.jobfinder.core.activation.DigestSet;
import com.jobfinder.core.billing.TrialReviews;
import com.jobfinder.core.billing.TrialReviews.Action;
import com.jobfinder.core.billing.TrialReviews.Outcome;
import com.jobfinder.core.billing.TrialReviews.Result;
import com.jobfinder.core.billing.TrialReviews.ReviewStatus;
import com.jobfinder.core.billing.TrialReviews.TrialState;
import com.jobfinder.core.billing.TrialReviews.View;
import com.jobfinder.core.compliance.RetentionTask;

/**
 * The trial review queue (docs/adr/0047-trial-review-queue.md): the gate records why it decided, an admin approves or
 * denies, the grant goes through the ledger once, a denial is final and every decision is audited.
 */
class TrialReviewTests extends TrialTestSupport {

    private static final BigDecimal M1 = new BigDecimal("30");
    private static final BigDecimal M2 = new BigDecimal("70");
    private static final String NOTE = "Checked the account: a genuine first signup";

    @Autowired
    TrialReviews reviews;

    @Autowired
    List<RetentionTask> retentionTasks;

    private UUID admin = UUID.randomUUID();

    /** A user the gate denied at M1 because the email domain is disposable. */
    private UUID deniedAtSignup() {
        UUID id = bareVerifiedUser();
        m1(id, "x-" + UUID.randomUUID() + "@mailinator.com", Instant.now());
        assertThat(trialState(id)).isEqualTo("DENIED");
        return id;
    }

    /** A user whose trial was granted at M1 and is then put on HOLD, as the enforced score would. */
    private UUID heldAfterM1() {
        UUID id = bareVerifiedUser();
        m1(id, newEmail(), Instant.now());
        jdbc.update("update trial_accounts set state = 'HOLD' where user_id = ?", id);
        return id;
    }

    private int auditRows(UUID userId) {
        return count("select count(*) from trial_review_decisions where user_id = ?", userId);
    }

    private int trialGrantLines(UUID userId) {
        return count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_GRANT'", userId);
    }

    // --- what the gate records ------------------------------------------------------------------------------------

    @Test
    void aHardRuleDenialRecordsItsReasonAndTheMilestoneReached() {
        UUID id = deniedAtSignup();

        TrialReviews.Item item = reviews.detail(id).orElseThrow().item();

        assertThat(item.state()).isEqualTo(TrialState.DENIED);
        assertThat(item.reviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(item.reasons()).containsExactly(new TrialReviews.Reason("disposable-domain", 0, true));
        assertThat(item.milestonesReached()).containsExactly("M1");
        assertThat(item.milestonesGranted()).isEmpty();
    }

    @Test
    void aSoftSignalIsRecordedWithItsPointsEvenWhenTheTrialIsGrantedInShadowMode() {
        DigestSet phone = digester.phones("0803 " + (100 + (int) (Math.random() * 899)) + " "
                + (1000 + (int) (Math.random() * 8999))).get(0);
        UUID first = bareVerifiedUser();
        UUID second = bareVerifiedUser();
        m1(first, newEmail(), Instant.now());
        m1(second, newEmail(), Instant.now());
        m2(first, fileDigest("a-" + UUID.randomUUID()), List.of(), List.of(phone), Instant.now());

        m2(second, fileDigest("b-" + UUID.randomUUID()), List.of(), List.of(phone), Instant.now());

        TrialReviews.Item item = reviews.detail(second).orElseThrow().item();
        assertThat(item.state()).isEqualTo(TrialState.GRANTED);
        assertThat(item.riskScore()).isEqualTo(30);
        assertThat(item.reasons()).contains(new TrialReviews.Reason("cv-phone", 30, false));
    }

    @Test
    void milestonesReachedAfterADenialAreRemembered() {
        UUID id = deniedAtSignup();

        m2(id, fileDigest("cv-" + UUID.randomUUID()), List.of(), List.of(), Instant.now());

        assertThat(reviews.detail(id).orElseThrow().item().milestonesReached()).containsExactly("M1", "M2");
        assertThat(trialGranted(id)).isZero();
    }

    // --- listing --------------------------------------------------------------------------------------------------

    @Test
    void thePendingQueueListsHeldAndDeniedAccountsOldestFirstWithAMaskedEmailOnly() {
        UUID older = deniedAtSignup();
        UUID newer = deniedAtSignup();
        UUID held = heldAfterM1();
        UUID granted = bareVerifiedUser();
        m1(granted, newEmail(), Instant.now());
        jdbc.update("update trial_accounts set created_at = now() - interval '3 days' where user_id = ?", older);
        jdbc.update("update trial_accounts set created_at = now() - interval '2 days' where user_id = ?", newer);
        jdbc.update("update trial_accounts set created_at = now() - interval '1 day' where user_id = ?", held);

        TrialReviews.Page page = reviews.list(View.PENDING, null, 0, 100);

        List<UUID> ids = page.items().stream().map(TrialReviews.Item::userId).toList();
        assertThat(ids).containsSubsequence(older, newer, held).doesNotContain(granted);
        TrialReviews.Item item = page.items().stream().filter(i -> i.userId().equals(older)).findFirst().orElseThrow();
        assertThat(item.maskedEmail()).matches("b\\*\\*\\*@example\\.test");
        assertThat(item.maskedEmail()).doesNotContain("bare-").doesNotContain(older.toString());
    }

    @Test
    void theStateFilterNarrowsTheQueue() {
        UUID denied = deniedAtSignup();
        UUID held = heldAfterM1();

        List<UUID> onlyHeld = reviews.list(View.PENDING, TrialState.HOLD, 0, 100).items().stream()
                .map(TrialReviews.Item::userId).toList();
        List<UUID> onlyDenied = reviews.list(View.PENDING, TrialState.DENIED, 0, 100).items().stream()
                .map(TrialReviews.Item::userId).toList();

        assertThat(onlyHeld).contains(held).doesNotContain(denied);
        assertThat(onlyDenied).contains(denied).doesNotContain(held);
    }

    @Test
    void theQueueIsPaginatedAndRejectsBadPages() {
        deniedAtSignup();
        deniedAtSignup();

        TrialReviews.Page page = reviews.list(View.PENDING, null, 0, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(2);
        assertThat(page.totalPages()).isEqualTo((int) Math.ceil(page.totalElements() / 1.0));
        assertThatThrownBy(() -> reviews.list(View.PENDING, null, -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.list(View.PENDING, null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.list(View.PENDING, null, 0, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDecidedAccountLeavesThePendingQueueAndShowsInTheDecidedView() {
        UUID id = deniedAtSignup();

        reviews.approve(id, admin, NOTE);

        assertThat(reviews.list(View.PENDING, null, 0, 100).items()).extracting(TrialReviews.Item::userId)
                .doesNotContain(id);
        TrialReviews.Item decided = reviews.list(View.DECIDED, null, 0, 100).items().stream()
                .filter(i -> i.userId().equals(id)).findFirst().orElseThrow();
        assertThat(decided.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(decided.decidedAt()).isNotNull();
    }

    // --- approve --------------------------------------------------------------------------------------------------

    @Test
    void approvingADeniedAccountGrantsTheFirstTrancheThroughTheLedgerAndAuditsIt() {
        UUID id = deniedAtSignup();

        Outcome outcome = reviews.approve(id, admin, NOTE);

        assertThat(outcome.result()).isEqualTo(Result.APPLIED);
        assertThat(outcome.creditsGranted()).isEqualByComparingTo(M1);
        assertThat(trialState(id)).isEqualTo("GRANTED");
        assertThat(balance(id)).isEqualByComparingTo(M1);
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_GRANT' "
                + "and idempotency_key = ?", id, "trial:" + id + ":M1")).isEqualTo(1);
        assertThat(count("select count(*) from trial_accounts where user_id = ? and expires_at > now() "
                + "and ended_at is null", id)).isEqualTo(1);
        assertThat(auditRows(id)).isEqualTo(1);
        TrialReviews.Decision decision = reviews.detail(id).orElseThrow().decisions().get(0);
        assertThat(decision.adminId()).isEqualTo(admin);
        assertThat(decision.action()).isEqualTo(Action.APPROVE);
        assertThat(decision.note()).isEqualTo(NOTE);
        assertThat(decision.creditsGranted()).isEqualByComparingTo(M1);
        assertThat(decision.decidedAt()).isBetween(Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now().plusSeconds(5));
    }

    @Test
    void approvingTwiceChangesNothingTheSecondTime() {
        UUID id = deniedAtSignup();
        reviews.approve(id, admin, NOTE);

        Outcome again = reviews.approve(id, UUID.randomUUID(), "A second look");

        assertThat(again.result()).isEqualTo(Result.REPLAYED);
        assertThat(again.creditsGranted()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance(id)).isEqualByComparingTo(M1);
        assertThat(trialGrantLines(id)).isEqualTo(1);
        assertThat(auditRows(id)).isEqualTo(1);
    }

    @Test
    void anApprovalReleasesEveryTrancheForTheMilestonesReached() {
        UUID id = deniedAtSignup();
        m2(id, fileDigest("cv-" + UUID.randomUUID()), List.of(), List.of(), Instant.now());

        Outcome outcome = reviews.approve(id, admin, NOTE);

        assertThat(outcome.creditsGranted()).isEqualByComparingTo(M1.add(M2));
        assertThat(trialGranted(id)).isEqualByComparingTo(M1.add(M2));
        assertThat(reviews.detail(id).orElseThrow().item().milestonesGranted()).containsExactly("M1", "M2");
    }

    @Test
    void approvingAHeldTrialDoesNotGrantTheTranchesItAlreadyHas() {
        UUID id = heldAfterM1();
        assertThat(trialGranted(id)).isEqualByComparingTo(M1);

        Outcome outcome = reviews.approve(id, admin, NOTE);

        assertThat(outcome.result()).isEqualTo(Result.APPLIED);
        assertThat(outcome.creditsGranted()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(trialGranted(id)).isEqualByComparingTo(M1);
        assertThat(trialGrantLines(id)).isEqualTo(1);
        assertThat(trialState(id)).isEqualTo("GRANTED");
    }

    @Test
    void anApprovedAccountIsNotDeniedAgainByTheGatesRulesAtLaterMilestones() {
        DigestSet sharedFile = fileDigest("shared-" + UUID.randomUUID());
        UUID owner = bareVerifiedUser();
        m1(owner, newEmail(), Instant.now());
        m2(owner, sharedFile, List.of(), List.of(), Instant.now());
        UUID reviewed = deniedAtSignup();
        reviews.approve(reviewed, admin, NOTE);

        // The exact CV file of one prior claimant is a hard match for anyone else.
        m2(reviewed, sharedFile, List.of(), List.of(), Instant.now());

        assertThat(trialState(reviewed)).isEqualTo("GRANTED");
        assertThat(trialGranted(reviewed)).isEqualByComparingTo(M1.add(M2));
    }

    @Test
    void anAccountThatNoLongerHasATrialToReleaseCannotBeApproved() {
        UUID id = heldAfterM1();
        jdbc.update("update trial_accounts set expires_at = now() - interval '1 day' where user_id = ?", id);

        Outcome outcome = reviews.approve(id, admin, NOTE);

        assertThat(outcome.result()).isEqualTo(Result.TRIAL_ENDED);
        assertThat(auditRows(id)).isZero();
    }

    // --- deny -----------------------------------------------------------------------------------------------------

    @Test
    void denyingIsFinalAndExpiresTheUnspentTrialCredits() {
        UUID id = heldAfterM1();

        Outcome outcome = reviews.deny(id, admin, "Same person as an earlier account");

        assertThat(outcome.result()).isEqualTo(Result.APPLIED);
        assertThat(trialState(id)).isEqualTo("DENIED");
        assertThat(balance(id)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_EXPIRY'", id))
                .isEqualTo(1);
        assertThat(reviews.detail(id).orElseThrow().item().reviewStatus()).isEqualTo(ReviewStatus.DENIED);
        assertThat(auditRows(id)).isEqualTo(1);

        Outcome approve = reviews.approve(id, admin, NOTE);

        assertThat(approve.result()).isEqualTo(Result.DENIED_FINAL);
        assertThat(trialState(id)).isEqualTo("DENIED");
        assertThat(balance(id)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(auditRows(id)).isEqualTo(1);
    }

    @Test
    void denyingTwiceChangesNothingTheSecondTime() {
        UUID id = deniedAtSignup();
        reviews.deny(id, admin, "Confirmed duplicate");

        Outcome again = reviews.deny(id, admin, "Confirmed duplicate");

        assertThat(again.result()).isEqualTo(Result.REPLAYED);
        assertThat(auditRows(id)).isEqualTo(1);
    }

    @Test
    void anApprovedAccountCannotBeDeniedAfterwards() {
        UUID id = deniedAtSignup();
        reviews.approve(id, admin, NOTE);

        Outcome outcome = reviews.deny(id, admin, "Changed my mind");

        assertThat(outcome.result()).isEqualTo(Result.ALREADY_APPROVED);
        assertThat(trialState(id)).isEqualTo("GRANTED");
        assertThat(auditRows(id)).isEqualTo(1);
    }

    // --- shared rules ---------------------------------------------------------------------------------------------

    @Test
    void anAccountThatWasNeverHeldOrDeniedHasNothingToReview() {
        UUID granted = bareVerifiedUser();
        m1(granted, newEmail(), Instant.now());

        assertThat(reviews.approve(granted, admin, NOTE).result()).isEqualTo(Result.NOT_IN_REVIEW);
        assertThat(reviews.deny(granted, admin, NOTE).result()).isEqualTo(Result.NOT_IN_REVIEW);
        assertThat(reviews.approve(UUID.randomUUID(), admin, NOTE).result()).isEqualTo(Result.NOT_FOUND);
        assertThat(reviews.deny(UUID.randomUUID(), admin, NOTE).result()).isEqualTo(Result.NOT_FOUND);
        assertThat(auditRows(granted)).isZero();
        assertThat(trialState(granted)).isEqualTo("GRANTED");
    }

    @Test
    void aNoteIsRequiredAndBounded() {
        UUID id = deniedAtSignup();

        assertThatThrownBy(() -> reviews.approve(id, admin, "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.deny(id, admin, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.deny(id, admin, "x".repeat(1001))).isInstanceOf(IllegalArgumentException.class);

        assertThat(trialState(id)).isEqualTo("DENIED");
        assertThat(auditRows(id)).isZero();
        assertThat(reviews.deny(id, admin, "x".repeat(1000)).result()).isEqualTo(Result.APPLIED);
    }

    // --- audit table ----------------------------------------------------------------------------------------------

    @Test
    void theAuditTableCannotBeUpdatedDeletedOrTruncated() {
        UUID id = deniedAtSignup();
        reviews.approve(id, admin, NOTE);

        assertThatThrownBy(() -> jdbc.update("update trial_review_decisions set note = 'edited' where user_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from trial_review_decisions where user_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate trial_review_decisions"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(auditRows(id)).isEqualTo(1);
    }

    @Test
    void theRetentionTaskPurgesDecisionsOlderThanTheRetentionOnly() {
        UUID oldUser = UUID.randomUUID();
        UUID recentUser = UUID.randomUUID();
        jdbc.update("""
                insert into trial_review_decisions (user_id, admin_id, action, state_before, note, decided_at)
                values (?, ?, 'DENY', 'DENIED', 'old', now() - interval '800 days'),
                       (?, ?, 'DENY', 'DENIED', 'recent', now() - interval '100 days')
                """, oldUser, admin, recentUser, admin);
        RetentionTask task = retentionTasks.stream().filter(t -> t.name().equals("trial-review-decisions"))
                .findFirst().orElseThrow();

        int purged = task.purge(Instant.now());

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(auditRows(oldUser)).isZero();
        assertThat(auditRows(recentUser)).isEqualTo(1);
    }
}
