package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;
import com.jobfinder.core.billing.RecordOutcome;
import com.jobfinder.core.billing.internal.AiCallStore.Inserted;

/**
 * Records usage and debits credits. Both writes happen in one transaction (a joined one when the caller has one):
 * the call row first, guarded by the unique request key, and only the writer that actually inserted it debits, so a
 * duplicate delivery can neither double-record nor double-charge, and a crash between the two cannot leave a call
 * without its debit.
 */
@Service
class LedgerService implements AiUsageLedger {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final BigDecimal MICRO = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal MAX_COST_USD = BigDecimal.valueOf(1_000_000);

    private final AiCallStore store;
    private final BillingProperties properties;
    private final Clock clock;

    LedgerService(AiCallStore store, BillingProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RecordOutcome record(AiUsage usage) {
        return record(usage, Instant.now(clock));
    }

    /** As {@link #record(AiUsage)} with an explicit time (tests use it to place usage on another day). */
    @Transactional
    RecordOutcome record(AiUsage usage, Instant at) {
        validate(usage);
        long costMicroUsd = toMicroUsd(usage.costUsd());
        UUID id = UUID.randomUUID();
        Optional<Inserted> inserted = store.insertCall(id, usage, costMicroUsd, at);
        if (inserted.isEmpty()) {
            log.debug("AI call {} was already recorded", usage.requestKey());
            return RecordOutcome.DUPLICATE;
        }
        UUID owner = inserted.get().userId();
        BigDecimal credits = credits(costMicroUsd);
        if (owner != null && credits.signum() > 0) {
            store.debit(owner, id, credits, at);
        }
        return RecordOutcome.RECORDED;
    }

    /** The price of a call in credits: its cost over what one credit stands for, to six decimals. */
    BigDecimal credits(long costMicroUsd) {
        return BigDecimal.valueOf(costMicroUsd).divide(BigDecimal.valueOf(properties.microUsdPerCredit()), 6,
                RoundingMode.HALF_UP);
    }

    private static long toMicroUsd(BigDecimal costUsd) {
        return costUsd.multiply(MICRO).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static void validate(AiUsage usage) {
        if (usage.requestKey() == null || usage.requestKey().isBlank() || usage.requestKey().length() > 100
                || usage.feature() == null || usage.feature().isBlank() || usage.provider() == null
                || usage.model() == null || usage.status() == null || usage.costUsd() == null
                || usage.costUsd().signum() < 0 || usage.costUsd().compareTo(MAX_COST_USD) > 0 || usage.inputTokens() < 0 || usage.outputTokens() < 0
                || usage.latencyMs() < 0) {
            throw new IllegalArgumentException("Invalid AI usage record");
        }
    }
}
