package com.jobfinder.core.billing.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.billing.internal.BillingProperties.Pack;
import com.jobfinder.core.billing.internal.PlanCatalog.Plan;
import com.jobfinder.core.billing.internal.SubscriptionStore.Status;
import com.jobfinder.core.billing.internal.SubscriptionStore.Subscription;

/**
 * The subscription lifecycle (docs/adr/0036-plans-credits-and-payments.md), driven by verified provider events and
 * by the expiry job. Every method runs in the caller's transaction (the webhook's, so the state change, the credit
 * grant and the "event handled" marker commit together or not at all) and takes the user's ledger lock first, so two
 * events for one user are applied one after the other.
 *
 * <p><b>Moving only forward.</b> A payment moves a subscription forward only when it covers a later period than the
 * one on record (or the same period and a later event time). Everything else is ordered by event time: a failure, a
 * cancellation or an end is applied only if it is not older than the newest event already applied. So a delayed or
 * replayed event can never turn an active subscription back into a past-due or canceled one, and a payment event for
 * an older period changes no state (its credits are still granted, once, if they were missed).
 */
@Service
class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** The event cannot be matched to a user yet (its companion event has not arrived); the webhook asks for a retry. */
    static class EventNotResolvableException extends RuntimeException {
        EventNotResolvableException(String message) {
            super(message);
        }
    }

    private final SubscriptionStore store;
    private final CreditLedgerStore ledger;
    private final CreditGrants grants;
    private final PlanCatalog plans;
    private final BillingProperties properties;

    SubscriptionService(SubscriptionStore store, CreditLedgerStore ledger, CreditGrants grants, PlanCatalog plans,
            BillingProperties properties) {
        this.store = store;
        this.ledger = ledger;
        this.grants = grants;
        this.plans = plans;
        this.properties = properties;
    }

    @Transactional
    void apply(ProviderEvent event, Instant now) {
        switch (event.kind()) {
            case PLAN_PAID -> planPaid(event, now);
            case PAYMENT_FAILED -> paymentFailed(event, now);
            case SUBSCRIPTION_LINKED -> linked(event, now);
            case SUBSCRIPTION_UPDATED -> updated(event, now);
            case SUBSCRIPTION_ENDED -> ended(event, now);
            case TOPUP_PAID -> topup(event, now);
            case IGNORED -> {
            }
        }
    }

    private Optional<Subscription> find(ProviderEvent event) {
        Optional<Subscription> found = Optional.empty();
        if (event.subscriptionRef() != null) {
            found = store.byRef(event.provider(), event.subscriptionRef());
        }
        if (found.isEmpty() && event.customer() != null) {
            found = store.liveByCustomer(event.provider(), event.customer());
        }
        return found;
    }

    /** The row again, after the user's lock is held (what the first read saw may have changed while waiting). */
    private Optional<Subscription> lockAndReload(Subscription found) {
        ledger.lock(found.userId());
        return store.find(found.id());
    }

    private void planPaid(ProviderEvent event, Instant now) {
        Subscription found = find(event).orElse(null);
        UUID userId = found != null ? found.userId() : event.userId();
        if (userId == null) {
            unresolvable(event, now, "payment for a subscription we cannot match yet");
            return;
        }
        ledger.lock(userId);
        Subscription sub = found == null ? null : store.find(found.id()).orElse(null);
        if (found != null && sub == null) {
            return;
        }
        if (sub == null) {
            sub = firstPayment(event, userId, now);
            if (sub == null) {
                return;
            }
        }
        Plan plan = plans.byId(sub.planId()).orElseThrow();
        if (sub.status() == Status.CANCELED) {
            // A payment for a subscription we already ended (late or replayed): its credits are owed once, no revival.
            grants.grantPlanPeriod(userId, plan, sub.id(), event.periodEnd(), false, now);
            return;
        }
        boolean first = sub.currentPeriodEnd() == null;
        boolean forward = first || event.periodEnd().isAfter(sub.currentPeriodEnd())
                || (event.periodEnd().equals(sub.currentPeriodEnd()) && event.at().isAfter(sub.lastEventAt()));
        if (!forward) {
            log.info("Payment event for an older period ignored for state: subscription={}", sub.id());
            grants.grantPlanPeriod(userId, plan, sub.id(), event.periodEnd(), false, now);
            return;
        }
        Instant lastEventAt = max(event.at(), sub.lastEventAt());
        Subscription next = new Subscription(sub.id(), sub.userId(), sub.planId(), sub.provider(),
                sub.providerRef() != null ? sub.providerRef() : event.subscriptionRef(),
                sub.providerCustomer() != null ? sub.providerCustomer() : event.customer(), Status.ACTIVE,
                event.periodEnd(), sub.cancelAtPeriodEnd(), null, lastEventAt, sub.createdAt(), now);
        store.save(next, now);
        grants.grantPlanPeriod(userId, plan, sub.id(), event.periodEnd(), !first, now);
    }

    /** The first payment of a checkout: the user's PENDING row becomes the subscription, or a row is made. */
    private Subscription firstPayment(ProviderEvent event, UUID userId, Instant now) {
        if (!ledger.userExists(userId)) {
            log.warn("Payment for an account that no longer exists ignored: provider={}", event.provider());
            return null;
        }
        if (store.live(userId).isPresent()) {
            log.warn("Payment for a second paid subscription ignored: user={} provider={}", userId,
                    event.provider());
            return null;
        }
        Optional<Subscription> pending = store.pending(userId, event.provider());
        if (pending.isPresent()) {
            return pending.get();
        }
        if (event.planCode() == null) {
            unresolvable(event, now, "payment without a plan");
            return null;
        }
        Optional<Plan> plan = plans.byCode(event.planCode());
        if (plan.isEmpty()) {
            log.warn("Payment for an unknown plan code ignored: provider={} plan={}", event.provider(),
                    event.planCode());
            return null;
        }
        return store.insertActive(userId, plan.get().id(), event.provider(), event.subscriptionRef(),
                event.customer(), null, event.at(), now);
    }

    /**
     * An event that needs a companion that has not arrived: asks for a redelivery (503) while the event is younger than
     * {@code app.billing.webhooks.unmatched-event-max-age}, so legitimate out-of-order delivery still works. Past that
     * age the companion is not coming, so the event is acknowledged (the caller returns normally and the webhook marks
     * it handled) and logged at WARN with its id and type only.
     */
    private void unresolvable(ProviderEvent event, Instant now, String why) {
        Instant limit = now.minus(properties.webhooks().unmatchedEventMaxAge());
        if (event.at().isBefore(limit)) {
            log.warn("Webhook event cannot be matched and is too old to wait for, acknowledged without effect: "
                    + "provider={} id={} type={}", event.provider().slug(), event.id(), event.type());
            return;
        }
        throw new EventNotResolvableException(why);
    }

    private void paymentFailed(ProviderEvent event, Instant now) {
        Optional<Subscription> found = find(event);
        if (found.isEmpty()) {
            return;
        }
        Subscription sub = lockAndReload(found.get()).orElse(null);
        if (sub == null || sub.status() != Status.ACTIVE || !event.at().isAfter(sub.lastEventAt())) {
            return;
        }
        store.save(new Subscription(sub.id(), sub.userId(), sub.planId(), sub.provider(), sub.providerRef(),
                sub.providerCustomer(), Status.PAST_DUE, sub.currentPeriodEnd(), sub.cancelAtPeriodEnd(),
                min(event.at(), now), event.at(), sub.createdAt(), now), now);
    }

    /** Learns the provider's subscription id and customer for a row we already hold; never changes state. */
    private void linked(ProviderEvent event, Instant now) {
        Optional<Subscription> byRef = store.byRef(event.provider(), event.subscriptionRef());
        if (byRef.isPresent()) {
            return;
        }
        Optional<Subscription> candidate = Optional.empty();
        if (event.userId() != null) {
            candidate = store.pending(event.userId(), event.provider());
        }
        if (candidate.isEmpty() && event.customer() != null) {
            candidate = store.liveByCustomer(event.provider(), event.customer())
                    .filter(s -> s.providerRef() == null);
        }
        if (candidate.isEmpty()) {
            if (event.provider() == Provider.PAYSTACK) {
                // The payment that creates the subscription is processed first; try again until it has been.
                unresolvable(event, now, "subscription created before its payment");
            }
            return;
        }
        Subscription sub = lockAndReload(candidate.get()).orElse(null);
        if (sub == null || sub.providerRef() != null || sub.status() == Status.CANCELED) {
            return;
        }
        store.save(new Subscription(sub.id(), sub.userId(), sub.planId(), sub.provider(), event.subscriptionRef(),
                sub.providerCustomer() != null ? sub.providerCustomer() : event.customer(), sub.status(),
                sub.currentPeriodEnd() != null ? sub.currentPeriodEnd() : event.periodEnd(),
                sub.cancelAtPeriodEnd(), sub.pastDueSince(), sub.lastEventAt(), sub.createdAt(), now), now);
    }

    private void updated(ProviderEvent event, Instant now) {
        Optional<Subscription> found = find(event);
        if (found.isEmpty()) {
            return;
        }
        Subscription sub = lockAndReload(found.get()).orElse(null);
        if (sub == null || sub.status() == Status.CANCELED || event.cancelAtPeriodEnd() == null
                || event.at().isBefore(sub.lastEventAt())) {
            return;
        }
        store.save(new Subscription(sub.id(), sub.userId(), sub.planId(), sub.provider(),
                sub.providerRef() != null ? sub.providerRef() : event.subscriptionRef(), sub.providerCustomer(),
                sub.status(), sub.currentPeriodEnd(), event.cancelAtPeriodEnd(), sub.pastDueSince(), event.at(),
                sub.createdAt(), now), now);
    }

    private void ended(ProviderEvent event, Instant now) {
        Optional<Subscription> found = find(event);
        if (found.isEmpty()) {
            return;
        }
        Subscription sub = lockAndReload(found.get()).orElse(null);
        if (sub == null || sub.status() == Status.CANCELED || event.at().isBefore(sub.lastEventAt())) {
            return;
        }
        store.save(downgraded(sub, event.at(), now), now);
    }

    private void topup(ProviderEvent event, Instant now) {
        if (event.userId() == null || event.packId() == null || event.paymentRef() == null) {
            log.warn("Top-up event without user, pack or payment reference ignored: provider={}",
                    event.provider());
            return;
        }
        Optional<Pack> pack = properties.pack(event.packId());
        if (pack.isEmpty()) {
            log.warn("Top-up for an unknown pack ignored: provider={} pack={}", event.provider(), event.packId());
            return;
        }
        boolean credited = grants.topup(event.userId(), pack.get().credits(), event.provider(), event.paymentRef(),
                now);
        if (credited) {
            log.info("Top-up credited: user={} pack={}", event.userId(), event.packId());
        }
    }

    private static Subscription downgraded(Subscription sub, Instant at, Instant now) {
        return new Subscription(sub.id(), sub.userId(), sub.planId(), sub.provider(), sub.providerRef(),
                sub.providerCustomer(), Status.CANCELED, sub.currentPeriodEnd(), false, null,
                max(at, sub.lastEventAt()), sub.createdAt(), now);
    }

    /**
     * Moves one overdue subscription back to Free (the expiry job): a user-canceled one at its period end, a past-due
     * one when the grace period has run out, an active one whose renewal never arrived within the grace period.
     * Re-checks under the user's lock, so a payment that arrives meanwhile wins.
     *
     * @return the subscription that was ended, or empty when it was no longer overdue
     */
    @Transactional
    Optional<Subscription> downgradeIfOverdue(UUID subscriptionId, Instant now) {
        Optional<Subscription> found = store.find(subscriptionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Subscription sub = lockAndReload(found.get()).orElse(null);
        if (sub == null || !sub.live()) {
            return Optional.empty();
        }
        Duration grace = properties.gracePeriod();
        Instant cutoff = now.minus(grace);
        boolean overdue = sub.status() == Status.ACTIVE
                ? sub.currentPeriodEnd() != null && sub.currentPeriodEnd().isBefore(now)
                        && (sub.cancelAtPeriodEnd() || sub.currentPeriodEnd().isBefore(cutoff))
                : (sub.pastDueSince() != null && sub.pastDueSince().isBefore(cutoff))
                        || (sub.cancelAtPeriodEnd() && sub.currentPeriodEnd() != null
                                && sub.currentPeriodEnd().isBefore(now));
        if (!overdue) {
            return Optional.empty();
        }
        store.save(downgraded(sub, now, now), now);
        log.info("Subscription ended, user is back on Free: subscription={} user={}", sub.id(), sub.userId());
        return Optional.of(sub);
    }

    List<Subscription> overdue(Instant now, int limit) {
        return store.overdue(now, properties.gracePeriod(), limit);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
