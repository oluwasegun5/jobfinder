package com.jobfinder.core.admin.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.billing.TrialReviews;
import com.jobfinder.core.billing.TrialReviews.Outcome;
import com.jobfinder.core.billing.TrialReviews.Result;
import com.jobfinder.core.billing.TrialReviews.ReviewStatus;
import com.jobfinder.core.billing.TrialReviews.TrialState;
import com.jobfinder.core.billing.TrialReviews.View;
import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The trial review queue (docs/adr/0047-trial-review-queue.md): the accounts the duplicate-account gate held or denied,
 * and the admin's decision on each. Everything under {@code /admin} requires the ADMIN role (see the security
 * configuration). Shows a masked email and the gate's reason codes only: no hash, no pepper, no matched account.
 */
@RestController
@RequestMapping("/admin/trials")
class AdminTrialsController {

    private static final Logger log = LoggerFactory.getLogger(AdminTrialsController.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    /** One rule or signal behind the gate's decision. {@code points} is 0 for a hard rule. */
    record ReasonResponse(String code, int points, boolean hard) {
    }

    /**
     * An account in the queue. {@code maskedEmail} is the first character of the local part and the whole domain.
     * {@code state} is the gate's current state (GRANTED once an admin approved); {@code reviewStatus} is PENDING until
     * an admin decides. {@code milestonesReached} and {@code milestonesGranted} hold milestone codes ({@code M1}..{@code M4}).
     */
    record TrialItemResponse(UUID userId, String maskedEmail, Instant signedUpAt, TrialState state,
            ReviewStatus reviewStatus, int riskScore, List<ReasonResponse> reasons, List<String> milestonesReached,
            List<String> milestonesGranted, Instant trialExpiresAt, Instant decidedAt) {

        static TrialItemResponse of(TrialReviews.Item item) {
            return new TrialItemResponse(item.userId(), item.maskedEmail(), item.signedUpAt(), item.state(),
                    item.reviewStatus(), item.riskScore(),
                    item.reasons().stream().map(r -> new ReasonResponse(r.code(), r.points(), r.hard())).toList(),
                    item.milestonesReached(), item.milestonesGranted(), item.trialExpiresAt(), item.decidedAt());
        }
    }

    /** One page (zero-based) of the queue, oldest first. */
    record TrialPageResponse(List<TrialItemResponse> items, int page, int size, long totalElements, int totalPages) {
    }

    /** One decision in the audit trail: who took it, what, when and the note. */
    record DecisionResponse(UUID adminId, TrialReviews.Action action, String note, BigDecimal creditsGranted,
            Instant decidedAt) {
    }

    /** An account and the decisions taken about it, newest first. */
    record TrialDetailResponse(TrialItemResponse item, List<DecisionResponse> decisions) {
    }

    /** Why the admin decided. 1 to 1000 characters; no personal data, please. */
    record ReviewRequest(@NotBlank @Size(max = 1000) String note) {
    }

    /**
     * {@code applied} is false when the same decision had been taken before (nothing changed). {@code creditsGranted} is
     * what this call added to the ledger; {@code item} is the account afterwards.
     */
    record DecisionOutcomeResponse(boolean applied, BigDecimal creditsGranted, TrialItemResponse item) {
    }

    private final TrialReviews reviews;

    AdminTrialsController(TrialReviews reviews) {
        this.reviews = reviews;
    }

    /**
     * The queue, oldest first. {@code view}: PENDING (default; HOLD or DENIED and not decided) or DECIDED.
     * {@code state}: HOLD, DENIED or GRANTED, to narrow by the gate's current state.
     */
    @GetMapping
    TrialPageResponse list(@RequestParam(defaultValue = "PENDING") View view,
            @RequestParam(required = false) TrialState state, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        try {
            TrialReviews.Page result = reviews.list(view, state, page, size);
            return new TrialPageResponse(result.items().stream().map(TrialItemResponse::of).toList(), result.page(),
                    result.size(), result.totalElements(), result.totalPages());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_page", e.getMessage());
        }
    }

    /** One account with its decisions. 404 {@code trial_not_found} when the user has no trial account. */
    @GetMapping("/{userId}")
    TrialDetailResponse detail(@PathVariable UUID userId) {
        TrialReviews.Detail detail = reviews.detail(userId).orElseThrow(AdminTrialsController::notFound);
        return new TrialDetailResponse(TrialItemResponse.of(detail.item()),
                detail.decisions().stream().map(d -> new DecisionResponse(d.adminId(), d.action(), d.note(),
                        d.creditsGranted(), d.decidedAt())).toList());
    }

    /**
     * Releases the staged grant (idempotent). 404 {@code trial_not_found}; 409 {@code trial_not_in_review},
     * {@code trial_denied_final} (an admin denied it), {@code trial_ended} (the trial has expired).
     */
    @PostMapping("/{userId}/approve")
    DecisionOutcomeResponse approve(@PathVariable UUID userId, @Valid @RequestBody ReviewRequest request) {
        UUID adminId = CurrentUser.require().id();
        Outcome outcome = run(() -> reviews.approve(userId, adminId, request.note()));
        log.info("Admin trial decision: action=APPROVE admin={} user={} result={}", adminId, userId, outcome.result());
        return respond(outcome);
    }

    /**
     * Denies the account for good (idempotent). 404 {@code trial_not_found}; 409 {@code trial_not_in_review},
     * {@code trial_already_approved}.
     */
    @PostMapping("/{userId}/deny")
    DecisionOutcomeResponse deny(@PathVariable UUID userId, @Valid @RequestBody ReviewRequest request) {
        UUID adminId = CurrentUser.require().id();
        Outcome outcome = run(() -> reviews.deny(userId, adminId, request.note()));
        log.info("Admin trial decision: action=DENY admin={} user={} result={}", adminId, userId, outcome.result());
        return respond(outcome);
    }

    private static Outcome run(java.util.function.Supplier<Outcome> decision) {
        try {
            return decision.get();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_note", e.getMessage());
        }
    }

    private static DecisionOutcomeResponse respond(Outcome outcome) {
        Result result = outcome.result();
        return switch (result) {
            case APPLIED, REPLAYED -> new DecisionOutcomeResponse(result == Result.APPLIED, outcome.creditsGranted(),
                    TrialItemResponse.of(outcome.item()));
            case NOT_FOUND -> throw notFound();
            case NOT_IN_REVIEW -> throw new ApiException(HttpStatus.CONFLICT, "trial_not_in_review",
                    "The account is not held or denied, so there is nothing to review");
            case DENIED_FINAL -> throw new ApiException(HttpStatus.CONFLICT, "trial_denied_final",
                    "An admin denied this account earlier; a denial is final");
            case ALREADY_APPROVED -> throw new ApiException(HttpStatus.CONFLICT, "trial_already_approved",
                    "An admin approved this account earlier; it cannot be denied");
            case TRIAL_ENDED -> throw new ApiException(HttpStatus.CONFLICT, "trial_ended",
                    "The trial has expired; there is nothing left to release");
        };
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "trial_not_found", "No trial account for that user");
    }
}
