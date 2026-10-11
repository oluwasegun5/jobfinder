package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.billing.internal.TrialAccountStore.State;
import com.jobfinder.core.billing.internal.TrialAccountStore.TrialAccount;

/**
 * The trial over time, after the gate's decisions: what the user sees ({@link #view}), whether the trial's lower daily
 * cap applies ({@link #running}), and the expiry of unspent trial credits {@code app.billing.trial.validity} after M1
 * ({@code TRIAL_EXPIRY}, once per user, key {@code trial-expiry:<userId>}).
 */
@Service
class TrialService {

    /** What {@code GET /billing/me} shows. {@code state}: PENDING (before M1), GRANTED, HOLD or DENIED. */
    record TrialView(String state, BigDecimal unlocked, BigDecimal remaining, Instant expiresAt,
            List<String> milestones) {
    }

    private static final Logger log = LoggerFactory.getLogger(TrialService.class);

    private final TrialAccountStore accounts;
    private final CreditLedgerStore ledger;
    private final SubscriptionStore subscriptions;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    TrialService(TrialAccountStore accounts, CreditLedgerStore ledger, SubscriptionStore subscriptions, JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.subscriptions = subscriptions;
        this.jdbc = jdbc;
        // Joins a caller's transaction (the gate may run inside one that already holds the user's ledger lock).
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    static String expiryKey(UUID userId) {
        return "trial-expiry:" + userId;
    }

    /** The trial as the user sees it; null for an account that existed before the trial (it never had one). */
    TrialView view(UUID userId, Instant now) {
        Optional<TrialAccount> found = accounts.find(userId);
        if (found.isEmpty()) {
            return new TrialView("PENDING", BigDecimal.ZERO, BigDecimal.ZERO, null, List.of());
        }
        TrialAccount a = found.get();
        if (a.state() == State.LEGACY) {
            return null;
        }
        BigDecimal unlocked = granted(userId);
        BigDecimal remaining = BigDecimal.ZERO;
        if (a.running(now)) {
            remaining = ledger.position(userId).planPortion().min(unlocked).max(BigDecimal.ZERO);
        }
        return new TrialView(a.state().name(), unlocked, remaining, a.expiresAt(),
                TrialMilestone.names(a.milestones()));
    }

    /** Whether the user's trial is live (so the trial daily cap applies): running and no paid plan. */
    boolean running(UUID userId, Instant now) {
        return accounts.find(userId).filter(a -> a.running(now)).isPresent() && subscriptions.live(userId).isEmpty();
    }

    BigDecimal granted(UUID userId) {
        return jdbc.sql("select coalesce(sum(delta), 0) from credit_ledger where user_id = :u and reason = 'TRIAL_GRANT'")
                .param("u", userId).query(BigDecimal.class).single();
    }

    /** Ends the user's trial if its time is up, expiring what is left of the trial credits. @return true if ended */
    boolean expireIfDue(UUID userId, Instant now) {
        Optional<TrialAccount> found = accounts.find(userId);
        if (found.isEmpty() || found.get().running(now) || found.get().endedAt() != null
                || found.get().expiresAt() == null
                || (found.get().state() != State.GRANTED && found.get().state() != State.HOLD)) {
            return false;
        }
        return Boolean.TRUE.equals(tx.execute(status -> expire(userId, now)));
    }

    /** The expiry job: ends every trial whose time is up. @return how many were ended */
    int expireDue(Instant now, int batchSize) {
        int ended = 0;
        while (true) {
            List<UUID> due = accounts.due(now, batchSize);
            if (due.isEmpty()) {
                return ended;
            }
            int before = ended;
            for (UUID id : due) {
                try {
                    if (Boolean.TRUE.equals(tx.execute(status -> expire(id, now)))) {
                        ended++;
                    }
                } catch (RuntimeException e) {
                    log.error("Ending the trial of user {} failed", id, e);
                }
            }
            if (ended == before) {
                return ended; // nothing could be ended in this batch: do not loop on it
            }
        }
    }

    private boolean expire(UUID userId, Instant now) {
        ledger.lock(userId);
        Optional<TrialAccount> found = accounts.find(userId);
        if (found.isEmpty()) {
            return false;
        }
        TrialAccount a = found.get();
        if (a.endedAt() != null || a.running(now) || a.expiresAt() == null
                || (a.state() != State.GRANTED && a.state() != State.HOLD)) {
            return false;
        }
        // A paid grant since M1 mixes plan and trial credits; renewals expire them from then on (ADR 0036).
        boolean paid = jdbc.sql("""
                select exists (select 1 from credit_ledger where user_id = :u and reason = 'PLAN_GRANT'
                                and created_at >= :since)
                """).param("u", userId).param("since", OffsetDateTime.ofInstant(a.startedAt(), ZoneOffset.UTC))
                .query(Boolean.class).single();
        if (!paid) {
            BigDecimal left = ledger.position(userId).planPortion().min(granted(userId));
            if (left.signum() > 0) {
                ledger.append(userId, left.negate(), LedgerReason.TRIAL_EXPIRY, null, expiryKey(userId), now);
            }
        }
        accounts.save(new TrialAccount(a.userId(), a.state(), a.milestones(), a.riskScore(), a.startedAt(),
                a.expiresAt(), now), now);
        log.info("Trial ended: user={}", userId);
        return true;
    }
}
