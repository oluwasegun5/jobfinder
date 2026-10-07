package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jobfinder.core.billing.AiDailyCapReachedException;
import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.billing.Allowance;
import com.jobfinder.core.billing.GateStatus;
import com.jobfinder.core.billing.InsufficientCreditsException;

/**
 * The gate in front of every user-attributed AI call: the credit balance must be above zero and the per-user daily
 * cap must not be used up (docs/adr/0036-plans-credits-and-payments.md, docs/adr/0025-ai-usage-ledger.md).
 *
 * <p>The cap: a day is a UTC calendar day, the cap is the most credits the user's AI calls may consume in it
 * ({@code app.billing.daily-cap-credits}, one total for all features). What a user spent is read from their credit
 * ledger, so the cap and the ledger cannot disagree. The balance is the last line's {@code balance_after}.
 *
 * <p>Before looking at the balance, a user on the Free plan is granted the credits of the current month if they have
 * none yet ({@link CreditGrants#grantFreeIfDue}), which is how existing users get their first grant and how a missed
 * monthly job is repaired.
 */
@Service
class DailyCapService implements AiUsageGate {

    private static final Logger log = LoggerFactory.getLogger(DailyCapService.class);

    private final AiCallStore store;
    private final CreditLedgerStore ledger;
    private final CreditGrants grants;
    private final BillingProperties properties;
    private final Clock clock;

    DailyCapService(AiCallStore store, CreditLedgerStore ledger, CreditGrants grants, BillingProperties properties,
            Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.grants = grants;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void requireAllowance(UUID userId, String feature) {
        requireAllowance(userId, feature, Instant.now(clock));
    }

    void requireAllowance(UUID userId, String feature, Instant now) {
        grants.grantFreeIfDue(userId, now);
        BigDecimal balance = ledger.balance(userId);
        if (balance.signum() <= 0) {
            log.info("AI credits exhausted: user={} feature={} balance={}", userId, feature, balance);
            throw new InsufficientCreditsException(balance);
        }
        Allowance allowance = allowance(userId, now);
        if (allowance.exhausted()) {
            log.info("AI daily cap reached: user={} feature={} used={} cap={}", userId, feature, allowance.used(),
                    allowance.dailyCap());
            throw new AiDailyCapReachedException(allowance.resetsAt(), now);
        }
    }

    @Override
    public GateStatus status(UUID userId) {
        Instant now = Instant.now(clock);
        grants.grantFreeIfDue(userId, now);
        if (ledger.balance(userId).signum() <= 0) {
            return GateStatus.INSUFFICIENT_CREDITS;
        }
        return allowance(userId, now).exhausted() ? GateStatus.DAILY_CAP_REACHED : GateStatus.OK;
    }

    @Override
    public Allowance allowance(UUID userId) {
        return allowance(userId, Instant.now(clock));
    }

    Allowance allowance(UUID userId, Instant now) {
        Instant dayStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant resetsAt = dayStart.plusSeconds(24 * 60 * 60);
        BigDecimal used = store.creditsUsed(userId, dayStart, resetsAt);
        if (!properties.capEnabled()) {
            return new Allowance(null, used, null, resetsAt);
        }
        BigDecimal cap = properties.dailyCapCredits();
        return new Allowance(cap, used, cap.subtract(used).max(BigDecimal.ZERO), resetsAt);
    }
}
