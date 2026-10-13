package com.jobfinder.core.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The review queue for trial accounts the duplicate-account gate held or denied (docs/adr/0047-trial-review-queue.md).
 * An admin lists them, reads why the gate decided as it did, and approves (the staged grant is released) or denies
 * (final). Nothing here returns a digest, a pepper or a full email address.
 */
public interface TrialReviews {

    /** Which accounts a listing returns. */
    enum View {
        /** HOLD or DENIED by the gate and not decided by an admin yet. */
        PENDING,
        /** An admin has approved or denied the account. */
        DECIDED
    }

    /** The gate's state for an account, as far as the queue cares. */
    enum TrialState {
        HOLD, DENIED, GRANTED
    }

    /** What an admin decided. */
    enum ReviewStatus {
        PENDING, APPROVED, DENIED
    }

    /**
     * One rule or signal that contributed to the gate's decision: its code ({@code cv-phone}), the points it added to the
     * risk score (0 for a hard rule) and whether it was a hard rule.
     */
    record Reason(String code, int points, boolean hard) {
    }

    /**
     * An account in the queue. {@code maskedEmail} keeps the first character of the local part and the whole domain.
     * {@code milestonesReached} and {@code milestonesGranted} are milestone codes ({@code M1}..{@code M4}); the
     * difference is the grant an approval releases. {@code reasons} is empty for an account decided before reasons were
     * recorded. {@code decidedAt} is set once an admin has decided.
     */
    record Item(UUID userId, String maskedEmail, Instant signedUpAt, TrialState state, ReviewStatus reviewStatus,
            int riskScore, List<Reason> reasons, List<String> milestonesReached, List<String> milestonesGranted,
            Instant trialExpiresAt, Instant decidedAt) {
    }

    /** One audit row: who decided what, when, with which note. */
    record Decision(UUID adminId, Action action, String note, BigDecimal creditsGranted, Instant decidedAt) {
    }

    /** What an admin can do to an account in the queue. */
    enum Action {
        APPROVE, DENY
    }

    /** An account with the decisions taken about it, newest first. */
    record Detail(Item item, List<Decision> decisions) {
    }

    /** One page (zero-based) of the queue. */
    record Page(List<Item> items, int page, int size, long totalElements, int totalPages) {
    }

    /** What happened to a decision request. */
    enum Result {
        /** The decision was taken by this call. */
        APPLIED,
        /** The same decision had been taken before: nothing changed. */
        REPLAYED,
        /** There is no trial account for the user. */
        NOT_FOUND,
        /** The account is not in HOLD or DENIED (or was never held), so there is nothing to review. */
        NOT_IN_REVIEW,
        /** An admin denied the account earlier and a denial is final: it cannot be approved. */
        DENIED_FINAL,
        /** An admin approved the account earlier: it cannot be denied afterwards. */
        ALREADY_APPROVED,
        /** The trial has ended (expired): there is nothing left to release. */
        TRIAL_ENDED
    }

    /** {@code item} is the account after the call (null for NOT_FOUND); {@code creditsGranted} is what this call appended. */
    record Outcome(Result result, Item item, BigDecimal creditsGranted) {
    }

    /**
     * Accounts in the queue, oldest first.
     *
     * @param state filters by the gate's current state; null for any
     * @param page  zero-based
     * @param size  1 to 100
     * @throws IllegalArgumentException when the page or size is out of range
     */
    Page list(View view, TrialState state, int page, int size);

    Optional<Detail> detail(UUID userId);

    /**
     * Releases the staged grant: every tranche for a milestone the account reached and has not been granted, as
     * {@code TRIAL_GRANT} ledger lines under the gate's own idempotency keys, and marks the account GRANTED. Later
     * milestones are granted without the gate's rules or score. Idempotent.
     *
     * @param note why, 1 to 1000 characters
     * @throws IllegalArgumentException when the note is blank or longer than 1000 characters
     */
    Outcome approve(UUID userId, UUID adminId, String note);

    /**
     * Denies the account for good. A HOLD trial's unspent trial credits expire. Idempotent.
     *
     * @param note why, 1 to 1000 characters
     * @throws IllegalArgumentException when the note is blank or longer than 1000 characters
     */
    Outcome deny(UUID userId, UUID adminId, String note);
}
