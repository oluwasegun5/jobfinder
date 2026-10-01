package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.util.UUID;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.identity.AuthTestSupport;

/** Plumbing for the billing tests: real users (so foreign keys hold) and a way to build usage records. */
abstract class BillingTestSupport extends AuthTestSupport {

    protected UUID newUser() throws Exception {
        String email = registerVerifiedUser();
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    protected static String newKey() {
        return "test:" + UUID.randomUUID();
    }

    /** A successful call by {@code userId} (null: system work) costing {@code costUsd} dollars. */
    protected static AiUsage usage(String key, UUID userId, String feature, String costUsd) {
        return new AiUsage(key, userId, feature, "test", "test-model", 100, 20, new BigDecimal(costUsd), 5,
                "test/v1", "test-prices", AiCallStatus.SUCCEEDED);
    }

    protected int calls(UUID userId) {
        return count("select count(*) from ai_calls where user_id = ?", userId);
    }

    protected int ledgerLines(UUID userId) {
        return count("select count(*) from credit_ledger where user_id = ?", userId);
    }

    protected BigDecimal balance(UUID userId) {
        return jdbc.queryForObject("select coalesce((select balance_after from credit_ledger where user_id = ? "
                + "order by id desc limit 1), 0)", BigDecimal.class, userId);
    }
}
