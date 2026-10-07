package com.jobfinder.core;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds a user's credit balance for tests that are about something other than credits (the daily cap, usage
 * recording), the way a paid top-up would: one TOPUP line on top of whatever the ledger holds. The gate is never
 * loosened for tests; a user simply has credits, as every real user does. Not for tests of the ledger itself.
 */
public final class TestCredits {

    private TestCredits() {
    }

    /** Enough that no existing cap or usage test comes near the balance. */
    public static final String PLENTY = "1000000";

    public static void seed(JdbcTemplate jdbc, UUID userId) {
        seed(jdbc, userId, PLENTY);
    }

    public static void seed(JdbcTemplate jdbc, UUID userId, String credits) {
        jdbc.update("""
                insert into credit_ledger (user_id, delta, reason, balance_after, topup_after, idempotency_key, created_at)
                select ?, c.v, 'TOPUP',
                       coalesce((select balance_after from credit_ledger where user_id = ? order by id desc limit 1), 0) + c.v,
                       coalesce((select topup_after from credit_ledger where user_id = ? order by id desc limit 1), 0) + c.v,
                       ?, now()
                  from (select cast(? as numeric) as v) c
                """, userId, userId, userId, "test:seed:" + UUID.randomUUID(), credits);
    }
}
