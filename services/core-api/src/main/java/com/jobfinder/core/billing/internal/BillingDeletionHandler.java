package com.jobfinder.core.billing.internal;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.billing.internal.SubscriptionStore.Subscription;
import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * Billing's share of an account deletion: the user's paid subscriptions are stopped at the provider (best effort: a
 * failure is queued for retry and never blocks the deletion), the user's subscription and credit ledger rows are
 * erased, and their AI calls lose the link to the person but stay, because the cost history belongs to the business
 * (no content is stored in it). {@code webhook_events} holds provider ids only, so it needs nothing. Runs in the
 * deleting transaction.
 */
@Component
class BillingDeletionHandler {

    private final JdbcClient jdbc;
    private final SubscriptionStore subscriptions;
    private final RemoteCancellations remote;
    private final Clock clock;

    BillingDeletionHandler(JdbcClient jdbc, SubscriptionStore subscriptions, RemoteCancellations remote,
            Clock clock) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.remote = remote;
        this.clock = clock;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        Instant now = Instant.now(clock);
        for (Subscription sub : subscriptions.remoteLive(event.userId())) {
            remote.cancelOrQueue(sub.provider(), sub.providerRef(), now);
        }
        subscriptions.deleteForUser(event.userId());
        jdbc.sql("delete from credit_ledger where user_id = :userId").param("userId", event.userId()).update();
        jdbc.sql("update ai_calls set user_id = null where user_id = :userId").param("userId", event.userId())
                .update();
    }
}
