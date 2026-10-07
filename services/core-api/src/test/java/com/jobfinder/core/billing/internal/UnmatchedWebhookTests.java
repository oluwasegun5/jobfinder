package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * An event that cannot be matched to a user: while it is younger than the configured age (24 h by default, from the
 * event's own timestamp) the provider is asked to redeliver (503, nothing recorded), because its companion may simply
 * not have arrived; older than that it is acknowledged (200) and recorded as handled, so the provider stops retrying.
 */
class UnmatchedWebhookTests extends PaymentTestSupport {

    private static Instant ago(Duration age) {
        return Instant.now().minus(age).truncatedTo(ChronoUnit.SECONDS);
    }

    private int recorded(String provider, String eventId) {
        return count("select count(*) from webhook_events where provider = ? and event_id = ?", provider, eventId);
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    @Test
    void anOldUnmatchableStripePaymentIsAcknowledgedAndRecordedSoItIsNotRetried() throws Exception {
        String eventId = newEventId();
        Instant at = ago(Duration.ofHours(25));
        String sub = unique("sub_");
        String body = stripeInvoicePaid(eventId, at, sub, unique("cus_"), null, null, daysFromNow(30));

        stripe(body).andExpect(status().isOk());
        assertThat(recorded("STRIPE", eventId)).isEqualTo(1);

        // A redelivery is a duplicate: still 200, still one row, and nothing was created for anybody.
        stripe(body).andExpect(status().isOk());
        assertThat(recorded("STRIPE", eventId)).isEqualTo(1);
        assertThat(count("select count(*) from subscriptions where provider_ref = ?", sub)).isZero();
    }

    @Test
    void aYoungUnmatchableStripePaymentStillAsksForARetryAndIsNotRecorded() throws Exception {
        String eventId = newEventId();
        String sub = unique("sub_");
        String cus = unique("cus_");
        String body = stripeInvoicePaid(eventId, ago(Duration.ofHours(23)), sub, cus, null, null, daysFromNow(30));

        stripe(body).andExpect(status().isServiceUnavailable());
        assertThat(recorded("STRIPE", eventId)).isZero();

        // Out-of-order delivery still works: the companion arrives, the redelivery is processed.
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        stripe(stripeSubscriptionCheckout(newEventId(), Instant.now(), sub, cus, account.id(), "pro"))
                .andExpect(status().isOk());
        stripe(body).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        assertThat(recorded("STRIPE", eventId)).isEqualTo(1);
    }

    @Test
    void anOldUnmatchablePaystackRenewalIsAcknowledgedAndRecorded() throws Exception {
        String body = paystackChargeRenewal(unique("ref_"), unique("CUS_"), ago(Duration.ofHours(25)));
        String eventId = Hmac.sha256Hex(body.getBytes(StandardCharsets.UTF_8));

        paystack(body).andExpect(status().isOk());
        assertThat(recorded("PAYSTACK", eventId)).isEqualTo(1);
        paystack(body).andExpect(status().isOk());
        assertThat(recorded("PAYSTACK", eventId)).isEqualTo(1);
    }

    @Test
    void aYoungUnmatchablePaystackRenewalStillAsksForARetry() throws Exception {
        String body = paystackChargeRenewal(unique("ref_"), unique("CUS_"), ago(Duration.ofHours(23)));
        String eventId = Hmac.sha256Hex(body.getBytes(StandardCharsets.UTF_8));

        paystack(body).andExpect(status().isServiceUnavailable());
        assertThat(recorded("PAYSTACK", eventId)).isZero();
    }

    @Test
    void aPaystackSubscriptionAnnouncementWhosePaymentNeverCameFollowsTheSameRule() throws Exception {
        String old = subscriptionCreatedAt(ago(Duration.ofHours(30)));
        paystack(old).andExpect(status().isOk());
        assertThat(recorded("PAYSTACK", Hmac.sha256Hex(old.getBytes(StandardCharsets.UTF_8)))).isEqualTo(1);

        String young = subscriptionCreatedAt(ago(Duration.ofHours(2)));
        paystack(young).andExpect(status().isServiceUnavailable());
        assertThat(recorded("PAYSTACK", Hmac.sha256Hex(young.getBytes(StandardCharsets.UTF_8)))).isZero();
    }

    private static String subscriptionCreatedAt(Instant createdAt) {
        return """
                {"event":"subscription.create","data":{"subscription_code":"%s","status":"active","createdAt":"%s",
                 "next_payment_date":"%s","customer":{"customer_code":"%s"},
                 "plan":{"plan_code":"PLN_PLACEHOLDER_pro_ngn"}}}"""
                .formatted(unique("SUB_"), createdAt, createdAt.plus(30, ChronoUnit.DAYS), unique("CUS_"));
    }
}
