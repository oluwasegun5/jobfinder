package com.jobfinder.core.billing.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Paystack webhooks, end to end through the real filter chain: a first payment (matched to its user by our metadata),
 * the subscription id arriving separately, a renewal (matched by customer code), a failure (past due, then Free after
 * the grace period), a cancellation, a duplicate delivery, a bad signature, replayed and out-of-order events, unknown
 * event types and top-ups. Paystack sends no event id and no timestamp header, which is why these tests also pin how
 * a delivery is identified (a hash of its body). Every key here is fake.
 */
class PaystackWebhookTests extends PaymentTestSupport {

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private LedgerService usageLedger;

    private static String sub() {
        return "SUB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String cus() {
        return "CUS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String ref() {
        return "jf_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static Instant monthAfter(Instant t) {
        return t.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
    }

    private static Instant periodEnd(Map<String, Object> row) {
        return ((Timestamp) row.get("current_period_end")).toInstant();
    }

    /** A user whose first payment (and subscription id) has been processed. */
    private Account subscribed(String sub, String cus, Instant paidAt) throws Exception {
        Account account = newAccount();
        pending(account.id(), "PAYSTACK");
        paystack(paystackChargePlan(account.id(), "pro", ref(), cus, paidAt)).andExpect(status().isOk());
        paystack(paystackSubscriptionCreate(sub, cus, monthAfter(paidAt))).andExpect(status().isOk());
        return account;
    }

    private void stubDisable(String sub) {
        providers().stubFor(get(urlPathEqualTo("/subscription/" + sub)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":true,\"data\":{\"subscription_code\":\"" + sub
                        + "\",\"email_token\":\"fake-email-token\"}}")));
        providers().stubFor(post(urlPathEqualTo("/subscription/disable")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"status\":true}")));
    }

    @Test
    void aFirstPaymentActivatesTheSubscriptionAndGrantsThePlanCreditsOnce() throws Exception {
        Account account = newAccount();
        String sub = sub();
        String cus = cus();
        pending(account.id(), "PAYSTACK");
        Instant paidAt = now();

        paystack(paystackChargePlan(account.id(), "pro", ref(), cus, paidAt)).andExpect(status().isOk());

        Map<String, Object> row = subscriptionRow(account.id());
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("provider_customer", cus)
                .containsEntry("cancel_at_period_end", false);
        assertThat(row.get("provider_ref")).isNull();
        assertThat(periodEnd(row)).isEqualTo(monthAfter(paidAt));
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("6000");

        // Paystack then announces the subscription: only its id is learned.
        paystack(paystackSubscriptionCreate(sub, cus, monthAfter(paidAt))).andExpect(status().isOk());
        assertThat(subscriptionRow(account.id())).containsEntry("provider_ref", sub).containsEntry("status", "ACTIVE");
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
    }

    @Test
    void theSubscriptionAnnouncementBeforeThePaymentAsksForARetry() throws Exception {
        Account account = newAccount();
        String sub = sub();
        String cus = cus();
        pending(account.id(), "PAYSTACK");
        Instant paidAt = now();
        String announcement = paystackSubscriptionCreate(sub, cus, monthAfter(paidAt));
        int events = webhookEvents("PAYSTACK");

        paystack(announcement).andExpect(status().isServiceUnavailable());
        assertThat(webhookEvents("PAYSTACK")).isEqualTo(events);

        paystack(paystackChargePlan(account.id(), "pro", ref(), cus, paidAt)).andExpect(status().isOk());
        paystack(announcement).andExpect(status().isOk());

        assertThat(subscriptionRow(account.id())).containsEntry("provider_ref", sub);
    }

    @Test
    void aRenewalIsMatchedByCustomerAndGrantsOncePerPeriodAndExpiresWhatWasLeft() throws Exception {
        String sub = sub();
        String cus = cus();
        Instant paidAt = now().minus(20, ChronoUnit.DAYS);
        Account account = subscribed(sub, cus, paidAt);
        usageLedger.record(usage(newKey(), account.id(), "parse_resume", "1.000"));

        Instant renewedAt = now();
        String renewal = paystackChargeRenewal(ref(), cus, renewedAt);
        paystack(renewal).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);
        assertThat(lines(account.id(), "PLAN_EXPIRY")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
        assertThat(periodEnd(subscriptionRow(account.id()))).isEqualTo(monthAfter(renewedAt));

        // The same payment again, byte for byte or with another reference for the same period: no second grant.
        paystack(renewal).andExpect(status().isOk());
        paystack(paystackChargeRenewal(ref(), cus, renewedAt)).andExpect(status().isOk());
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);
    }

    @Test
    void aFailedPaymentIsPastDueThenBackToFreeAfterTheGracePeriodAndTheRemoteSubscriptionIsStopped()
            throws Exception {
        String sub = sub();
        String cus = cus();
        stubDisable(sub);
        Account account = subscribed(sub, cus, now().minusSeconds(60));

        paystack(paystackPaymentFailed(sub, cus, now().plusSeconds(10))).andExpect(status().isOk());

        assertThat(subscriptionStatus(account.id())).isEqualTo("PAST_DUE");
        jobs.expire(now().plusSeconds(86_400));
        assertThat(subscriptionStatus(account.id())).isEqualTo("PAST_DUE");

        jobs.expire(now().plusSeconds(4 * 86_400L));
        assertThat(subscriptionStatus(account.id())).isEqualTo("CANCELED");
        providers().verify(getRequestedFor(urlPathEqualTo("/subscription/" + sub)));
        providers().verify(postRequestedFor(urlPathEqualTo("/subscription/disable"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(sub)));
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
        assertThat(grants.grantFreeIfDue(account.id(), now().plusSeconds(4 * 86_400L))).isTrue();
    }

    @Test
    void aLaterPaymentBringsAPastDueSubscriptionBack() throws Exception {
        String sub = sub();
        String cus = cus();
        Account account = subscribed(sub, cus, now().minusSeconds(60));
        paystack(paystackPaymentFailed(sub, cus, now().plusSeconds(10))).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("PAST_DUE");

        paystack(paystackChargeRenewal(ref(), cus, now().plus(31, ChronoUnit.DAYS))).andExpect(status().isOk());

        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);
    }

    @Test
    void cancellingKeepsThePlanUntilThePeriodEndsThenTheUserIsOnFree() throws Exception {
        String sub = sub();
        String cus = cus();
        Instant paidAt = now().minusSeconds(60);
        Account account = subscribed(sub, cus, paidAt);
        Instant end = periodEnd(subscriptionRow(account.id()));

        paystack(paystackNotRenew(sub, cus, now().plusSeconds(5))).andExpect(status().isOk());

        assertThat(subscriptionRow(account.id())).containsEntry("status", "ACTIVE")
                .containsEntry("cancel_at_period_end", true);
        jobs.expire(end.minusSeconds(10));
        jobs.expire(end.plusSeconds(10));
        assertThat(subscriptionStatus(account.id())).isEqualTo("CANCELED");
        // Already stopped at Paystack by the cancel: nothing more to call.
        providers().verify(0, postRequestedFor(urlPathEqualTo("/subscription/disable"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(sub)));
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void aDuplicateDeliveryChangesNothing() throws Exception {
        Account account = newAccount();
        pending(account.id(), "PAYSTACK");
        String body = paystackChargePlan(account.id(), "pro", ref(), cus(), now());

        paystack(body).andExpect(status().isOk());
        int events = webhookEvents("PAYSTACK");
        paystack(body).andExpect(status().isOk());
        paystack(body).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
        assertThat(webhookEvents("PAYSTACK")).isEqualTo(events);
        assertThat(count("select count(*) from subscriptions where user_id = ?", account.id())).isEqualTo(1);
    }

    @Test
    void aBadSignatureIsRefusedWithNoDetailAndChangesNothing() throws Exception {
        Account account = newAccount();
        pending(account.id(), "PAYSTACK");
        String body = paystackChargePlan(account.id(), "pro", ref(), cus(), now());
        int events = webhookEvents("PAYSTACK");

        paystack(body, paystackSignature(body + " ")).andExpect(status().isBadRequest())
                .andExpect(content().string(""));
        paystack(body, null).andExpect(status().isBadRequest()).andExpect(content().string(""));
        paystack(body, "garbage").andExpect(status().isBadRequest());
        // SHA-256 of the right secret instead of SHA-512, and a different body's signature.
        paystack(body, Hmac.hex("HmacSHA256", com.jobfinder.core.PaymentFixtures.PAYSTACK_KEY,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8))).andExpect(status().isBadRequest());
        paystack(body, paystackSignature("{\"event\":\"charge.success\",\"data\":{}}"))
                .andExpect(status().isBadRequest());

        assertThat(webhookEvents("PAYSTACK")).isEqualTo(events);
        assertThat(lines(account.id(), "PLAN_GRANT")).isZero();
        assertThat(subscriptionStatus(account.id())).isEqualTo("PENDING");
        // The signature is checked over the raw bytes, so the right one still works afterwards.
        paystack(body).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
    }

    @Test
    void aReplayedOrOutOfOrderEventNeverRegressesTheSubscription() throws Exception {
        String sub = sub();
        String cus = cus();
        Instant firstPaid = now().minus(40, ChronoUnit.DAYS);
        Account account = newAccount();
        pending(account.id(), "PAYSTACK");
        String firstRef = ref();

        // The renewal arrives first (a user matched through the customer needs the first payment, so give the first
        // payment's metadata on a charge that is processed first, then replay it after the renewal).
        String firstPayment = paystackChargePlan(account.id(), "pro", firstRef, cus, firstPaid);
        paystack(firstPayment).andExpect(status().isOk());
        paystack(paystackSubscriptionCreate(sub, cus, monthAfter(firstPaid))).andExpect(status().isOk());
        Instant renewedAt = now();
        paystack(paystackChargeRenewal(ref(), cus, renewedAt)).andExpect(status().isOk());
        Instant secondEnd = monthAfter(renewedAt);
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);

        // The first payment again, as a new delivery (another body, same payment): no state change, no new grant.
        paystack(firstPayment.replace("\"amount\":2500000", "\"amount\": 2500000")).andExpect(status().isOk());
        assertThat(periodEnd(subscriptionRow(account.id()))).isEqualTo(secondEnd);
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);

        // A failure and a cancellation from before the renewal, delivered late, are stale.
        paystack(paystackPaymentFailed(sub, cus, firstPaid.plusSeconds(3600))).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        paystack(paystackNotRenew(sub, cus, firstPaid.plusSeconds(7200))).andExpect(status().isOk());
        assertThat(subscriptionRow(account.id())).containsEntry("cancel_at_period_end", false);
        // A newer failure does apply.
        paystack(paystackPaymentFailed(sub, cus, renewedAt.plusSeconds(60))).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("PAST_DUE");
        // And the older payment replayed now does not undo it.
        paystack(firstPayment.replace("\"amount\":2500000", "\"amount\":  2500000")).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("PAST_DUE");
    }

    @Test
    void anUnknownEventTypeIsAcknowledgedCountedAndNotStored() throws Exception {
        var before = meters.find("billing.webhooks").tags("provider", "paystack", "outcome", "ignored").counter();
        double count = before == null ? 0 : before.count();
        int events = webhookEvents("PAYSTACK");

        paystack("{\"event\":\"transfer.success\",\"data\":{\"id\":" + System.nanoTime() + "}}")
                .andExpect(status().isOk());

        var after = meters.find("billing.webhooks").tags("provider", "paystack", "outcome", "ignored").counter();
        assertThat(after.count()).isEqualTo(count + 1);
        assertThat(webhookEvents("PAYSTACK")).isEqualTo(events);
    }

    @Test
    void aTopUpIsCreditedExactlyOnce() throws Exception {
        Account account = newAccount();
        String reference = ref();

        paystack(paystackChargePack(account.id(), "pack_small", reference, now())).andExpect(status().isOk());
        paystack(paystackChargePack(account.id(), "pack_small", reference, now())).andExpect(status().isOk());
        paystack(paystackChargePack(account.id(), "pack_small", reference, now().minusSeconds(5)))
                .andExpect(status().isOk());

        assertThat(lines(account.id(), "TOPUP")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("2000");
    }

    @Test
    void aPaymentForAnAccountThatNoLongerExistsIsAcknowledgedAndCreditsNobody() throws Exception {
        UUID gone = UUID.randomUUID();

        paystack(paystackChargePack(gone, "pack_small", ref(), now())).andExpect(status().isOk());
        paystack(paystackChargePlan(gone, "pro", ref(), cus(), now())).andExpect(status().isOk());

        assertThat(count("select count(*) from credit_ledger where user_id = ?", gone)).isZero();
        assertThat(count("select count(*) from subscriptions where user_id = ?", gone)).isZero();
    }
}
