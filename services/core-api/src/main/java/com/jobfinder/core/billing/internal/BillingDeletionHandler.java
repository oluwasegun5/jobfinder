package com.jobfinder.core.billing.internal;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * Billing's share of an account deletion: the user's credit ledger is erased, and their AI calls lose the link to
 * the person but stay, because the cost history belongs to the business (no content is stored in it). Runs in the
 * deleting transaction.
 */
@Component
class BillingDeletionHandler {

    private final JdbcClient jdbc;

    BillingDeletionHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        jdbc.sql("delete from credit_ledger where user_id = :userId").param("userId", event.userId()).update();
        jdbc.sql("update ai_calls set user_id = null where user_id = :userId").param("userId", event.userId())
                .update();
    }
}
