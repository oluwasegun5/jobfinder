package com.jobfinder.core.billing.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * A verified webhook delivery in the terms billing understands, whatever the provider called it. The adapters map
 * their own event types to a {@link Kind}; everything they do not care about is {@link Kind#IGNORED}.
 *
 * @param id              the provider's event id (Stripe's {@code evt_...}; for Paystack, which sends none, a hash of
 *                        the raw body, identical on every redelivery)
 * @param type            the provider's own event type, for logs and metrics
 * @param at              when it happened at the provider (used to order events, never to trust one over another
 *                        beyond that)
 * @param userId          our user id from the metadata we put on the checkout, when the event carries it
 * @param planCode        the plan code from that metadata or the provider's plan, when present
 * @param packId          the credit pack id from the metadata (top-ups)
 * @param subscriptionRef the provider's subscription id/code
 * @param customer        the provider's customer id/code
 * @param paymentRef      the provider's payment reference (top-ups are credited once per payment)
 * @param periodEnd       when the period this payment covers (or this subscription's current one) ends
 * @param cancelAtPeriodEnd for {@link Kind#SUBSCRIPTION_UPDATED}: whether the subscription will stop at period end
 * @param amountMinor     for {@link Kind#PLAN_PAID} and {@link Kind#TOPUP_PAID}: what was actually paid, in minor units
 *                        (cents, kobo), before tax; null when the event does not say
 * @param currency        the ISO currency of {@code amountMinor}, as the provider wrote it (any case)
 */
record ProviderEvent(Provider provider, String id, String type, Kind kind, Instant at, UUID userId, String planCode,
        String packId, String subscriptionRef, String customer, String paymentRef, Instant periodEnd,
        Boolean cancelAtPeriodEnd, Long amountMinor, String currency) {

    enum Kind {
        /** A subscription payment succeeded: the first one activates, later ones renew. */
        PLAN_PAID,
        /** A subscription payment failed. */
        PAYMENT_FAILED,
        /** The provider created the subscription and told us its id (links our row to it). */
        SUBSCRIPTION_LINKED,
        /** Cancel-at-period-end changed, or the period moved. */
        SUBSCRIPTION_UPDATED,
        /** The subscription ended at the provider. */
        SUBSCRIPTION_ENDED,
        /** A one-off credit pack was paid. */
        TOPUP_PAID,
        /**
         * A refund or a dispute at the provider. Nothing is reversed automatically: it is logged at WARN once (it is
         * claimed like any event) so an admin can take the credits back with the manual adjustment endpoint.
         */
        REFUND_OR_DISPUTE,
        /** Nothing billing acts on. */
        IGNORED
    }

    static ProviderEvent ignored(Provider provider, String id, String type, Instant at) {
        return new ProviderEvent(provider, id, type, Kind.IGNORED, at, null, null, null, null, null, null, null,
                null, null, null);
    }
}
