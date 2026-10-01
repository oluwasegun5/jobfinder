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

/**
 * The per-user daily cap: a day is a UTC calendar day, the cap is the most credits the user's AI calls may consume
 * in it ({@code app.billing.daily-cap-credits}, one total for all features). What a user spent is read from their
 * credit ledger, so the cap and the ledger cannot disagree.
 */
@Service
class DailyCapService implements AiUsageGate {

    private static final Logger log = LoggerFactory.getLogger(DailyCapService.class);

    private final AiCallStore store;
    private final BillingProperties properties;
    private final Clock clock;

    DailyCapService(AiCallStore store, BillingProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void requireAllowance(UUID userId, String feature) {
        requireAllowance(userId, feature, Instant.now(clock));
    }

    void requireAllowance(UUID userId, String feature, Instant now) {
        Allowance allowance = allowance(userId, now);
        if (allowance.exhausted()) {
            log.info("AI daily cap reached: user={} feature={} used={} cap={}", userId, feature, allowance.used(),
                    allowance.dailyCap());
            throw new AiDailyCapReachedException(allowance.resetsAt(), now);
        }
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
