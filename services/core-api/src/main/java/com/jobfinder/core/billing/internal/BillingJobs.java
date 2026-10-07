package com.jobfinder.core.billing.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jobfinder.core.billing.internal.SubscriptionStore.Status;
import com.jobfinder.core.billing.internal.SubscriptionStore.Subscription;


/**
 * The work of billing's two scheduled jobs (docs/adr/0036-plans-credits-and-payments.md):
 * <ul>
 * <li><b>Free grant</b>: gives every active user without it this month's free credits. Idempotent per user and month
 * ({@link CreditGrants#grantFreeIfDue}), so running it twice, or after the gate already granted lazily, writes nothing
 * new. The lock is an optimisation, not what makes it safe.</li>
 * <li><b>Expiry</b>: moves subscriptions that are overdue back to Free (a canceled one at its period end, a past-due
 * one when the grace period is over), stops them at the provider best-effort, and retries the stops that failed.</li>
 * </ul>
 * {@link BillingJobsScheduler} runs them on the clock; tests call them directly.
 */
@Component
class BillingJobs {

    private static final Logger log = LoggerFactory.getLogger(BillingJobs.class);

    private final CreditGrants grants;
    private final SubscriptionService subscriptions;
    private final RemoteCancellations remote;
    private final BillingProperties properties;

    BillingJobs(CreditGrants grants, SubscriptionService subscriptions, RemoteCancellations remote,
            BillingProperties properties) {
        this.grants = grants;
        this.subscriptions = subscriptions;
        this.remote = remote;
        this.properties = properties;
    }

    /** Grants this month's free credits to every user missing them. @return how many users were granted */
    int grantFree(Instant now) {
        String period = CreditGrants.period(now);
        int granted = 0;
        UUID after = null;
        while (true) {
            List<UUID> ids = grants.usersMissingFreeGrant(period, after, properties.jobs().batchSize());
            if (ids.isEmpty()) {
                break;
            }
            for (UUID id : ids) {
                try {
                    if (grants.grantFreeIfDue(id, now)) {
                        granted++;
                    }
                } catch (RuntimeException e) {
                    log.error("Free grant failed for user {}", id, e);
                }
            }
            after = ids.get(ids.size() - 1);
        }
        log.info("Free grant job for {}: granted {} users", period, granted);
        return granted;
    }

    /** Ends overdue subscriptions and retries failed remote stops. @return how many subscriptions were ended */
    int expire(Instant now) {
        int ended = 0;
        for (Subscription due : subscriptions.overdue(now, properties.jobs().batchSize())) {
            try {
                var done = subscriptions.downgradeIfOverdue(due.id(), now);
                if (done.isPresent()) {
                    ended++;
                    Subscription sub = done.get();
                    // A plan the user canceled is already stopped at the provider; a lapsed or unpaid one is not.
                    if (sub.providerRef() != null && !(sub.status() == Status.ACTIVE && sub.cancelAtPeriodEnd())) {
                        remote.cancelOrQueue(sub.provider(), sub.providerRef(), now);
                    }
                }
            } catch (RuntimeException e) {
                log.error("Expiring subscription {} failed", due.id(), e);
            }
        }
        int retried = remote.retryDue(now, properties.jobs().batchSize());
        log.info("Billing expiry job: ended {} subscriptions, cleared {} queued cancellations", ended, retried);
        return ended;
    }
}
