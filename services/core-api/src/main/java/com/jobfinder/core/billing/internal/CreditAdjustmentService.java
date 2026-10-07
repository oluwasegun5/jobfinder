package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.billing.CreditAdjustments;
import com.jobfinder.core.billing.internal.CreditLedgerStore.KeyedLine;

/**
 * Writes the manual {@code REFUND_ADJUSTMENT} lines (docs/adr/0036-plans-credits-and-payments.md). It goes through
 * {@link CreditLedgerStore#append}, the only writer of the ledger: one transaction, the user's advisory lock, the
 * balance and top-up figures computed from the line before, and the unique idempotency key. The key is namespaced
 * ({@code refund:}) so an admin's key can never collide with a grant, expiry or top-up key.
 */
@Service
class CreditAdjustmentService implements CreditAdjustments {

    static final String KEY_PREFIX = "refund:";
    static final int MAX_KEY = 100;
    static final int MAX_REASON = 500;

    private static final Logger log = LoggerFactory.getLogger(CreditAdjustmentService.class);

    private final CreditLedgerStore ledger;
    private final Clock clock;

    CreditAdjustmentService(CreditLedgerStore ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Outcome adjust(UUID userId, BigDecimal delta, String idempotencyKey, String reason) {
        if (delta == null || delta.signum() >= 0 || delta.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("An adjustment is a negative number of credits with at most six decimals");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY
                || reason == null || reason.isBlank() || reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("An adjustment needs an idempotency key and a reason of bounded length");
        }
        String key = KEY_PREFIX + idempotencyKey.strip();
        ledger.lock(userId);
        if (!ledger.userExists(userId)) {
            return new Outcome(Result.USER_NOT_FOUND, null);
        }
        boolean written = ledger.append(userId, delta, LedgerReason.REFUND_ADJUSTMENT, null, key,
                Instant.now(clock), reason.strip());
        if (written) {
            log.info("Credit adjustment applied: user={} delta={} key={}", userId, delta.toPlainString(), key);
            return new Outcome(Result.APPLIED, ledger.balance(userId));
        }
        Optional<KeyedLine> existing = ledger.lineByKey(key);
        if (existing.isPresent() && existing.get().userId().equals(userId)
                && existing.get().delta().compareTo(delta) == 0) {
            return new Outcome(Result.REPLAYED, ledger.balance(userId));
        }
        log.warn("Credit adjustment refused, the key was used for something else: user={} key={}", userId, key);
        return new Outcome(Result.KEY_CONFLICT, ledger.balance(userId));
    }
}
