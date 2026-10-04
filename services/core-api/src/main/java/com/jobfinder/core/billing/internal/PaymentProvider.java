package com.jobfinder.core.billing.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.jobfinder.core.billing.internal.BillingProperties.Pack;
import com.jobfinder.core.billing.internal.BillingProperties.Price;
import com.jobfinder.core.billing.internal.PlanCatalog.Plan;

/**
 * What billing needs from a payment provider (docs/adr/0036-plans-credits-and-payments.md): start a hosted checkout,
 * stop a subscription, and verify and read a webhook. Two adapters, {@code StripeProvider} and
 * {@code PaystackProvider}, each a thin HTTP client (no vendor SDK). Card data never reaches us: the user pays on
 * the provider's own page, and nothing here sees or logs card, token or secret values.
 */
interface PaymentProvider {

    /** Metadata we put on every checkout (and so get back, signed, in the webhook): the user, plan or pack. */
    String META_USER = "jf_user";
    String META_KIND = "jf_kind";
    String META_ITEM = "jf_item";
    String KIND_PLAN = "plan";
    String KIND_PACK = "pack";

    Provider provider();

    /** Who is paying and where the provider sends the browser back to. {@code email} is personal data: never log it. */
    record CheckoutContext(UUID userId, String email, String reference, String returnUrl, String cancelUrl) {
    }

    /** The hosted page to send the user to. */
    record CheckoutLink(String url) {
    }

    /** Starts the subscription checkout of {@code plan} at {@code price} (in {@code currency}). */
    CheckoutLink checkoutPlan(CheckoutContext context, Plan plan, String currency, Price price);

    /** Starts the one-off checkout of a credit pack. */
    CheckoutLink checkoutPack(CheckoutContext context, Pack pack, String currency, Price price);

    /** Stops renewals; the user keeps what they paid for until the period ends. Idempotent. */
    void cancelAtPeriodEnd(String providerRef);

    /** Ends the subscription now (account deletion). Idempotent: an already-ended one is not an error. */
    void cancelNow(String providerRef);

    /**
     * Verifies the signature over the RAW body, and only then parses it.
     *
     * @param headers the request headers, names lowercased
     * @throws InvalidWebhookException when the signature is missing or wrong, or the verified body is not an event
     */
    ProviderEvent verifyAndParse(byte[] rawBody, Map<String, String> headers, Instant now);

    /** A webhook that must be refused with 400 and no detail: bad signature, stale timestamp, or unreadable body. */
    class InvalidWebhookException extends RuntimeException {
        InvalidWebhookException(String reason) {
            super(reason);
        }
    }

    /** The provider could not be reached or refused the request. The message never contains request data. */
    class PaymentProviderException extends RuntimeException {
        PaymentProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
