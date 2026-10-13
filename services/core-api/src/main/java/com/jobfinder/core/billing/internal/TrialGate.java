package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.activation.DigestSet;
import com.jobfinder.core.activation.EmailVerified;
import com.jobfinder.core.activation.FirstMatchesViewed;
import com.jobfinder.core.activation.IdentityDigest;
import com.jobfinder.core.activation.OnboardingCompleted;
import com.jobfinder.core.activation.ResumeParsed;
import com.jobfinder.core.billing.TrialReviews.Reason;
import com.jobfinder.core.billing.internal.TrialAccountStore.State;
import com.jobfinder.core.billing.internal.TrialAccountStore.TrialAccount;
import com.jobfinder.core.billing.internal.TrialClaimStore.Claim;
import com.jobfinder.core.billing.internal.TrialClaimStore.Kind;

/**
 * The staged trial grant and its duplicate-account gate (docs/adr/0042-trial-credit-abuse-gate.md). Listens to the four
 * activation milestones after their transaction commits and decides, in a transaction of its own, whether the user's
 * tranche is granted:
 * <ol>
 * <li>Hard rules, always enforced: the account email (canonical) or the Google account was already claimed by another
 * account (deleted ones included), the email domain is disposable, or the CV file is the exact file of one prior
 * claimant. A hard match denies the trial; the account works normally.</li>
 * <li>Soft signals add up to a risk score. With {@code score-enforced=false} (shadow mode, rollout step 2) the score
 * and the would-be outcome are logged and the tranche is granted anyway; enforced, 30 holds and 60 denies.</li>
 * <li>The circuit breaker: more grants in the last hour than {@code hourly-ceiling} would hold new claims (logged only
 * in shadow mode).</li>
 * </ol>
 * Concurrency: every digest involved is locked ({@code pg_advisory_xact_lock}, sorted), then every claim on them is read
 * {@code FOR UPDATE} across all key versions, so two accounts of one identity are decided one after the other and only
 * the first is granted. Claims are written at grant time, so a later deletion cannot remove them. A failure is logged
 * and never undoes the change that published the milestone. Logs carry the user id and rule names, never a digest.
 */
@Component
class TrialGate {

    enum Outcome {
        GRANTED, HELD, DENIED, SKIPPED
    }

    /** One identity value to check and claim: what kind of claim it would be, and its digests. */
    record Candidate(Kind kind, DigestSet digests) {
    }

    private static final Logger log = LoggerFactory.getLogger(TrialGate.class);

    private final TrialProperties properties;
    private final TrialAccountStore accounts;
    private final TrialClaimStore claims;
    private final TrialReviewStore reviews;
    private final CreditLedgerStore ledger;
    private final DisposableDomains disposable;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    TrialGate(TrialProperties properties, TrialAccountStore accounts, TrialClaimStore claims, TrialReviewStore reviews,
            CreditLedgerStore ledger, DisposableDomains disposable, JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.properties = properties;
        this.accounts = accounts;
        this.claims = claims;
        this.reviews = reviews;
        this.ledger = ledger;
        this.disposable = disposable;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    static String grantKey(UUID userId, TrialMilestone milestone) {
        return "trial:" + userId + ":" + milestone.code();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(EmailVerified event) {
        List<Candidate> candidates = new ArrayList<>();
        if (event.email() != null) {
            candidates.add(new Candidate(Kind.EMAIL, event.email()));
        }
        if (event.googleSubject() != null) {
            candidates.add(new Candidate(Kind.GOOGLE_SUB, event.googleSubject()));
        }
        safely(event.userId(), TrialMilestone.EMAIL_VERIFIED, event.at(), candidates, event.emailDomain(),
                event.viaGoogle());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(ResumeParsed event) {
        List<Candidate> candidates = new ArrayList<>();
        if (event.file() != null) {
            candidates.add(new Candidate(Kind.CV_FILE, event.file()));
        }
        event.cvEmails().forEach(d -> candidates.add(new Candidate(Kind.CV_EMAIL, d)));
        event.cvPhones().forEach(d -> candidates.add(new Candidate(Kind.CV_PHONE, d)));
        safely(event.userId(), TrialMilestone.CV_PARSED, event.at(), candidates, null, false);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(OnboardingCompleted event) {
        safely(event.userId(), TrialMilestone.ONBOARDING_COMPLETED, event.at(), List.of(), null, false);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(FirstMatchesViewed event) {
        safely(event.userId(), TrialMilestone.MATCHES_VIEWED, event.at(), List.of(), null, false);
    }

    private void safely(UUID userId, TrialMilestone milestone, Instant at, List<Candidate> candidates, String domain,
            boolean viaGoogle) {
        if (!properties.enabled()) {
            return;
        }
        try {
            tx.execute(status -> decide(userId, milestone, at, candidates, domain, viaGoogle));
        } catch (RuntimeException e) {
            log.error("Trial decision failed: user={} milestone={}", userId, milestone.code(), e);
        }
    }

    Outcome decide(UUID userId, TrialMilestone milestone, Instant at, List<Candidate> candidates, String domain,
            boolean viaGoogle) {
        Instant now = clock.instant();
        ledger.lock(userId);
        if (!verified(userId)) {
            return Outcome.SKIPPED;
        }
        Optional<TrialAccount> found = accounts.find(userId);
        if (found.isEmpty() && milestone != TrialMilestone.EMAIL_VERIFIED) {
            return Outcome.SKIPPED; // nothing before M1
        }
        if (found.isPresent() && milestone == TrialMilestone.EMAIL_VERIFIED) {
            // M1 again: a Google account linked later. Claim it too, so it cannot start another trial.
            recordLinkedIdentities(found.get(), candidates, now);
            return Outcome.SKIPPED;
        }
        if (milestone == TrialMilestone.EMAIL_VERIFIED && candidates.isEmpty()) {
            // No usable claim key (the normaliser could not read the address): no trial rather than an unbounded one.
            accounts.save(new TrialAccount(userId, State.DENIED, 0, 0, at, null, null), now);
            reviews.markReached(userId, milestone.bit());
            reviews.addReasons(userId, List.of(new Reason("no-claim-key", 0, true)));
            log.info("Trial denied: user={} milestone={} rule=no-claim-key", userId, milestone.code());
            return Outcome.DENIED;
        }
        if (found.isPresent() && !eligible(found.get(), milestone, at, now)) {
            rememberReachedWhileDenied(found.get(), milestone, at);
            return Outcome.SKIPPED;
        }
        TrialAccount account = found.orElse(null);

        List<IdentityDigest> all = new ArrayList<>();
        candidates.forEach(c -> all.addAll(c.digests().digests()));
        claims.lock(all);
        List<Claim> existing = claims.findForUpdate(all);

        String hardRule = null;
        int points = 0;
        List<String> signals = new ArrayList<>();
        List<Reason> reasons = new ArrayList<>();
        if (milestone == TrialMilestone.EMAIL_VERIFIED && domain != null && disposable.isDisposable(domain)) {
            hardRule = "disposable-domain";
        }
        boolean cvEmailOfOther = false;
        boolean cvEmailIsOwn = false;
        boolean cvPhoneOfOther = false;
        List<Claim> othersSeen = new ArrayList<>();
        for (Candidate c : candidates) {
            for (Claim claim : matching(existing, c.digests())) {
                boolean other = !claim.ownedBy(userId);
                if (other) {
                    othersSeen.add(claim);
                }
                switch (c.kind()) {
                    case EMAIL -> {
                        if (other && claim.kind() == Kind.EMAIL && hardRule == null) {
                            hardRule = "account-email";
                        }
                    }
                    case GOOGLE_SUB -> {
                        if (other && claim.kind() == Kind.GOOGLE_SUB && hardRule == null) {
                            hardRule = "google-account";
                        }
                    }
                    case CV_FILE -> {
                        if (other && claim.kind() == Kind.CV_FILE) {
                            if (claim.claimantCount() == 1 && hardRule == null) {
                                hardRule = "cv-file";
                            } else if (claim.claimantCount() > 1 && !signals.contains("cv-file-shared")) {
                                signals.add("cv-file-shared");
                                points += properties.score().cvFileShared();
                                reasons.add(new Reason("cv-file-shared", properties.score().cvFileShared(), false));
                            }
                        }
                    }
                    case CV_EMAIL -> {
                        if (other && (claim.kind() == Kind.EMAIL || claim.kind() == Kind.CV_EMAIL)) {
                            cvEmailOfOther = true;
                        }
                        if (!other && claim.kind() == Kind.EMAIL) {
                            cvEmailIsOwn = true;
                        }
                    }
                    case CV_PHONE -> {
                        if (other && claim.kind() == Kind.CV_PHONE) {
                            cvPhoneOfOther = true;
                        }
                    }
                }
            }
        }
        if (cvEmailOfOther) {
            signals.add("cv-email");
            points += properties.score().cvEmail();
            reasons.add(new Reason("cv-email", properties.score().cvEmail(), false));
        }
        if (cvEmailIsOwn) {
            signals.add("cv-email-is-account-email");
            points += properties.score().cvEmailIsAccountEmail();
            reasons.add(new Reason("cv-email-is-account-email", properties.score().cvEmailIsAccountEmail(), false));
        }
        if (cvPhoneOfOther) {
            signals.add("cv-phone");
            points += properties.score().cvPhone();
            reasons.add(new Reason("cv-phone", properties.score().cvPhone(), false));
        }
        if (viaGoogle) {
            signals.add("google-sign-in");
            points += properties.score().googleSignIn();
            reasons.add(new Reason("google-sign-in", properties.score().googleSignIn(), false));
        }
        if (milestone == TrialMilestone.ONBOARDING_COMPLETED && account != null && account.startedAt() != null
                && at.isBefore(account.startedAt().plus(properties.score().fastOnboardingWithin()))) {
            signals.add("fast-onboarding");
            points += properties.score().fastOnboarding();
            reasons.add(new Reason("fast-onboarding", properties.score().fastOnboarding(), false));
        }
        int score = (account == null ? 0 : account.riskScore()) + points;
        Instant claimExpiry = now.plus(properties.claimRetention());
        claims.seen(dedupe(othersSeen), now, claimExpiry);

        Instant startedAt = account == null ? at : account.startedAt();
        Instant trialExpires = account == null ? at.plus(properties.validity()) : account.expiresAt();
        int milestones = account == null ? 0 : account.milestones();

        // An admin's approval (ADR 0047) overrules the hard rules and the score for the account's later milestones.
        boolean approvedByAdmin = reviews.approved(userId);
        if (hardRule != null && !approvedByAdmin) {
            accounts.save(new TrialAccount(userId, State.DENIED, milestones, score, startedAt, null, null), now);
            reviews.markReached(userId, milestone.bit());
            reasons.add(new Reason(hardRule, 0, true));
            reviews.addReasons(userId, reasons);
            log.info("Trial denied: user={} milestone={} rule={}", userId, milestone.code(), hardRule);
            return Outcome.DENIED;
        }

        Outcome byScore = score >= properties.score().denyAt() ? Outcome.DENIED
                : score >= properties.score().holdAt() ? Outcome.HELD : Outcome.GRANTED;
        boolean tripped = grantsInLastHour(now) >= properties.hourlyCeiling();
        boolean onHold = account != null && account.state() == State.HOLD;
        if ((tripped || onHold) && byScore == Outcome.GRANTED) {
            byScore = Outcome.HELD; // a held trial stays held until reviewed
            if (tripped) {
                reasons.add(new Reason("hourly-ceiling", 0, false));
            }
        }
        if (approvedByAdmin) {
            byScore = Outcome.GRANTED;
        }
        if (!signals.isEmpty() || byScore != Outcome.GRANTED) {
            log.info("Trial score: user={} milestone={} score={} signals={} breaker={} outcome={} enforced={}", userId,
                    milestone.code(), score, signals, tripped, byScore, properties.scoreEnforced());
        }
        Outcome outcome = properties.scoreEnforced() ? byScore : Outcome.GRANTED;
        if (outcome == Outcome.DENIED) {
            accounts.save(new TrialAccount(userId, State.DENIED, milestones, score, startedAt, null, null), now);
            reviews.markReached(userId, milestone.bit());
            reviews.addReasons(userId, reasons);
            return Outcome.DENIED;
        }
        State state = account != null && account.state() == State.HOLD ? State.HOLD : State.GRANTED;
        if (outcome == Outcome.HELD) {
            state = State.HOLD;
            if (milestone != TrialMilestone.EMAIL_VERIFIED) {
                accounts.save(new TrialAccount(userId, state, milestones, score, startedAt, trialExpires, null), now);
                reviews.markReached(userId, milestone.bit());
                reviews.addReasons(userId, reasons);
                log.info("Trial tranche held: user={} milestone={}", userId, milestone.code());
                return Outcome.HELD;
            }
        }

        int bit = milestone.bit();
        for (Candidate c : candidates) {
            claims.upsert(c.kind(), c.digests().current(), userId, bit, now, claimExpiry);
        }
        claims.markOwn(userId, bit, now, claimExpiry);
        accounts.save(new TrialAccount(userId, state, milestones | bit, score, startedAt, trialExpires, null), now);
        reviews.markReached(userId, bit);
        reviews.addReasons(userId, reasons);
        BigDecimal tranche = properties.tranche(milestone);
        if (tranche.signum() > 0) {
            ledger.append(userId, tranche, LedgerReason.TRIAL_GRANT, null, grantKey(userId, milestone), now);
        }
        log.info("Trial tranche granted: user={} milestone={} credits={}", userId, milestone.code(), tranche);
        return state == State.HOLD ? Outcome.HELD : Outcome.GRANTED;
    }

    /**
     * A denied account stays denied, but the milestones it goes on to reach are remembered (never granted) so that an
     * admin's approval can release them (docs/adr/0047-trial-review-queue.md). Same rules as a grant: once, and M4 only
     * on a later UTC day than M1.
     */
    private void rememberReachedWhileDenied(TrialAccount account, TrialMilestone milestone, Instant at) {
        if (account.state() != State.DENIED || account.endedAt() != null || account.startedAt() == null) {
            return;
        }
        if (milestone == TrialMilestone.MATCHES_VIEWED
                && !at.atZone(ZoneOffset.UTC).toLocalDate().isAfter(account.startedAt().atZone(ZoneOffset.UTC).toLocalDate())) {
            return;
        }
        reviews.markReached(account.userId(), milestone.bit());
    }

    /**
     * Claims identities an account gains after its M1 (a Google account linked to a password account) with the
     * account's milestones, unless another account already holds them. A denied account claims nothing.
     */
    private void recordLinkedIdentities(TrialAccount account, List<Candidate> candidates, Instant now) {
        if (account.state() == State.DENIED || candidates.isEmpty()) {
            return;
        }
        List<IdentityDigest> all = new ArrayList<>();
        candidates.forEach(c -> all.addAll(c.digests().digests()));
        claims.lock(all);
        List<Claim> existing = claims.findForUpdate(all);
        Instant expires = now.plus(properties.claimRetention());
        for (Candidate c : candidates) {
            boolean heldByOther = matching(existing, c.digests()).stream()
                    .anyMatch(claim -> claim.kind() == c.kind() && !claim.ownedBy(account.userId()));
            if (!heldByOther && account.milestones() != 0) {
                claims.upsert(c.kind(), c.digests().current(), account.userId(), account.milestones(), now, expires);
            }
        }
    }

    /** A later milestone counts only while the trial runs, once, and M4 only on a later UTC day than M1. */
    private static boolean eligible(TrialAccount a, TrialMilestone milestone, Instant at, Instant now) {
        if (a.state() == State.DENIED || a.state() == State.LEGACY || a.endedAt() != null) {
            return false;
        }
        if (milestone.in(a.milestones())) {
            return false;
        }
        if (a.expiresAt() == null || !now.isBefore(a.expiresAt()) || !at.isBefore(a.expiresAt())) {
            return false;
        }
        if (milestone == TrialMilestone.MATCHES_VIEWED) {
            LocalDate m1 = a.startedAt().atZone(ZoneOffset.UTC).toLocalDate();
            return at.atZone(ZoneOffset.UTC).toLocalDate().isAfter(m1);
        }
        return true;
    }

    private static List<Claim> matching(List<Claim> existing, DigestSet set) {
        Set<String> wanted = new HashSet<>();
        set.digests().forEach(d -> wanted.add(d.keyVersion() + ":" + d.hex()));
        return existing.stream().filter(c -> wanted.contains(c.keyVersion() + ":" + c.hex())).toList();
    }

    private static List<Claim> dedupe(List<Claim> list) {
        Set<UUID> ids = new HashSet<>();
        return list.stream().filter(c -> ids.add(c.id())).toList();
    }

    private boolean verified(UUID userId) {
        return jdbc.sql("select exists (select 1 from users where id = :u and email_verified_at is not null "
                + "and deleted_at is null)").param("u", userId).query(Boolean.class).single();
    }

    private int grantsInLastHour(Instant now) {
        return jdbc.sql("select count(*) from credit_ledger where reason = 'TRIAL_GRANT' and created_at > :since")
                .param("since", OffsetDateTime.ofInstant(now.minus(1, ChronoUnit.HOURS), ZoneOffset.UTC))
                .query(Integer.class).single();
    }
}
