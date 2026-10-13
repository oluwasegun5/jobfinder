package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.billing.TrialReviews;
import com.jobfinder.core.billing.internal.TrialAccountStore.State;
import com.jobfinder.core.billing.internal.TrialAccountStore.TrialAccount;
import com.jobfinder.core.billing.internal.TrialReviewStore.QueueRow;
import com.jobfinder.core.billing.internal.TrialReviewStore.ReviewState;

/**
 * The trial review queue (docs/adr/0047-trial-review-queue.md). Approving releases the staged grant through the
 * ledger under the gate's own idempotency keys; denying is final and expires the unspent trial credits. Each decision
 * runs under the user's ledger lock, which the gate takes too, and writes the audit row in the same transaction.
 */
@Service
class TrialReviewService implements TrialReviews {

    static final int MAX_NOTE_LENGTH = 1000;
    static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(TrialReviewService.class);

    private final TrialAccountStore accounts;
    private final TrialReviewStore reviews;
    private final TrialClaimStore claims;
    private final CreditLedgerStore ledger;
    private final TrialService trials;
    private final TrialProperties properties;
    private final Clock clock;

    TrialReviewService(TrialAccountStore accounts, TrialReviewStore reviews, TrialClaimStore claims,
            CreditLedgerStore ledger, TrialService trials, TrialProperties properties, Clock clock) {
        this.accounts = accounts;
        this.reviews = reviews;
        this.claims = claims;
        this.ledger = ledger;
        this.trials = trials;
        this.properties = properties;
        this.clock = clock;
    }

    /** {@code jane@gmail.com} becomes {@code j***@gmail.com}: the first character and the whole domain. */
    static String maskEmail(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    private static String requireNote(String note) {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A note explaining the decision is required");
        }
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("The note is at most " + MAX_NOTE_LENGTH + " characters");
        }
        return trimmed;
    }

    private static List<String> codes(int milestones) {
        List<String> codes = new ArrayList<>();
        for (TrialMilestone m : TrialMilestone.values()) {
            if (m.in(milestones)) {
                codes.add(m.code());
            }
        }
        return codes;
    }

    private static Item item(QueueRow row) {
        return new Item(row.userId(), maskEmail(row.email()), row.signedUpAt(), row.state(), row.reviewStatus(),
                row.riskScore(), row.reasons(), codes(row.milestonesReached() | row.milestonesGranted()),
                codes(row.milestonesGranted()), row.trialExpiresAt(), row.decidedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Page list(View view, TrialState state, int page, int size) {
        if (page < 0 || page > 1_000_000) {
            throw new IllegalArgumentException("page must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        long total = reviews.queueSize(view, state);
        List<Item> items = reviews.queue(view, state, page * size, size).stream().map(TrialReviewService::item)
                .toList();
        return new Page(items, page, size, total, (int) ((total + size - 1) / size));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Detail> detail(UUID userId) {
        return reviews.row(userId).map(row -> new Detail(item(row), reviews.decisions(userId)));
    }

    private Outcome outcome(Result result, UUID userId, BigDecimal creditsGranted) {
        Item item = reviews.row(userId).map(TrialReviewService::item).orElse(null);
        return new Outcome(result, item, creditsGranted);
    }

    @Override
    @Transactional
    public Outcome approve(UUID userId, UUID adminId, String note) {
        String reason = requireNote(note);
        Instant now = clock.instant();
        ledger.lock(userId);
        Optional<TrialAccount> found = accounts.find(userId);
        Optional<ReviewState> review = found.isEmpty() ? Optional.empty() : reviews.lockReview(userId);
        if (found.isEmpty() || review.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, null, BigDecimal.ZERO);
        }
        TrialAccount account = found.get();
        switch (review.get().reviewStatus()) {
            case APPROVED -> {
                return outcome(Result.REPLAYED, userId, BigDecimal.ZERO);
            }
            case DENIED -> {
                return outcome(Result.DENIED_FINAL, userId, BigDecimal.ZERO);
            }
            case PENDING -> {
            }
        }
        if (account.state() != State.HOLD && account.state() != State.DENIED) {
            return outcome(Result.NOT_IN_REVIEW, userId, BigDecimal.ZERO);
        }
        if (account.endedAt() != null || (account.expiresAt() != null && !now.isBefore(account.expiresAt()))) {
            return outcome(Result.TRIAL_ENDED, userId, BigDecimal.ZERO);
        }

        // Every milestone the account reached (M1 at least: the row exists because of it) whose tranche is not granted.
        int reached = review.get().milestonesReached() | account.milestones() | TrialMilestone.EMAIL_VERIFIED.bit();
        int granted = account.milestones();
        BigDecimal creditsGranted = BigDecimal.ZERO;
        for (TrialMilestone milestone : TrialMilestone.values()) {
            if (!milestone.in(reached) || milestone.in(granted)) {
                continue;
            }
            BigDecimal tranche = properties.tranche(milestone);
            if (tranche.signum() > 0 && ledger.append(userId, tranche, LedgerReason.TRIAL_GRANT, null,
                    TrialGate.grantKey(userId, milestone), now)) {
                creditsGranted = creditsGranted.add(tranche);
            }
            granted |= milestone.bit();
        }
        // A denied account has no expiry yet: its trial starts counting now.
        Instant expiresAt = account.expiresAt() != null ? account.expiresAt() : now.plus(properties.validity());
        Instant startedAt = account.startedAt() != null ? account.startedAt() : now;
        accounts.save(new TrialAccount(userId, State.GRANTED, granted, account.riskScore(), startedAt, expiresAt, null),
                now);
        claims.markOwn(userId, granted, now, now.plus(properties.claimRetention()));
        reviews.setReview(userId, ReviewStatus.APPROVED, reached | granted, now);
        reviews.appendDecision(userId, adminId, Action.APPROVE, TrialState.valueOf(account.state().name()), reason,
                creditsGranted, now);
        log.info("Trial approved: user={} admin={} credits={}", userId, adminId, creditsGranted);
        return outcome(Result.APPLIED, userId, creditsGranted);
    }

    @Override
    @Transactional
    public Outcome deny(UUID userId, UUID adminId, String note) {
        String reason = requireNote(note);
        Instant now = clock.instant();
        ledger.lock(userId);
        Optional<TrialAccount> found = accounts.find(userId);
        Optional<ReviewState> review = found.isEmpty() ? Optional.empty() : reviews.lockReview(userId);
        if (found.isEmpty() || review.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, null, BigDecimal.ZERO);
        }
        TrialAccount account = found.get();
        switch (review.get().reviewStatus()) {
            case DENIED -> {
                return outcome(Result.REPLAYED, userId, BigDecimal.ZERO);
            }
            case APPROVED -> {
                return outcome(Result.ALREADY_APPROVED, userId, BigDecimal.ZERO);
            }
            case PENDING -> {
            }
        }
        if (account.state() != State.HOLD && account.state() != State.DENIED) {
            return outcome(Result.NOT_IN_REVIEW, userId, BigDecimal.ZERO);
        }

        // Unspent trial credits expire (spent ones stay spent); the trial is over for good.
        trials.expireUnspentCredits(account, now);
        accounts.save(new TrialAccount(userId, State.DENIED, account.milestones(), account.riskScore(),
                account.startedAt(), account.expiresAt(), now), now);
        reviews.setReview(userId, ReviewStatus.DENIED, review.get().milestonesReached() | account.milestones(), now);
        reviews.appendDecision(userId, adminId, Action.DENY, TrialState.valueOf(account.state().name()), reason,
                BigDecimal.ZERO, now);
        log.info("Trial denied by admin: user={} admin={}", userId, adminId);
        return outcome(Result.APPLIED, userId, BigDecimal.ZERO);
    }
}
