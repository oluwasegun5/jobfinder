package com.jobfinder.core.billing.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.PaymentFixtures;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stripe webhooks, end to end through the real filter chain (no JWT, signature over the raw body): a first payment,
 * a renewal, a failure (past due, then back to Free after the grace period), a cancellation, a duplicate delivery, a
 * bad signature, replayed and out-of-order events, unknown event types and top-ups. Every secret here is fake.
 */
class StripeWebhookTests extends PaymentTestSupport {

    @Autowired
    private MeterRegistry meters;

    private static String sub() {
        return "sub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private static String cus() {
        return "cus_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    /** A user whose first payment has been processed; the period ends at {@code end}. */
    private Account subscribed(String sub, String cus, Instant paidAt, Instant end) throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        stripe(stripeInvoicePaid(newEventId(), paidAt, sub, cus, account.id(), "pro", end)).andExpect(status().isOk());
        return account;
    }

    @Test
    void aFirstPaymentActivatesTheSubscriptionAndGrantsThePlanCreditsOnce() throws Exception {
        Account account = newAccount();
        String sub = sub();
        String cus = cus();
        pending(account.id(), "STRIPE");
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant end = daysFromNow(30);

        stripe(stripeSubscriptionCheckout(newEventId(), now, sub, cus, account.id(), "pro")).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("PENDING");
        assertThat(subscriptionRow(account.id())).containsEntry("provider_ref", sub);

        stripe(stripeInvoicePaid(newEventId(), now, sub, cus, account.id(), "pro", end)).andExpect(status().isOk());

        Map<String, Object> row = subscriptionRow(account.id());
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("provider_ref", sub)
                .containsEntry("provider_customer", cus).containsEntry("cancel_at_period_end", false);
        assertThat(((java.sql.Timestamp) row.get("current_period_end")).toInstant()).isEqualTo(end);
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
        // A user on a paid plan gets no free grant on top.
        assertThat(grants.grantFreeIfDue(account.id(), Instant.now())).isFalse();
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
    }

    @Test
    void thePaymentMayArriveBeforeTheCheckoutLinkBecauseItCarriesTheUserItself() throws Exception {
        Account account = newAccount();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        // No pending row at all: the metadata on the invoice is enough.
        stripe(stripeInvoicePaid(newEventId(), now, sub(), cus(), account.id(), "pro", daysFromNow(30)))
                .andExpect(status().isOk());

        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void aRenewalGrantsOncePerPeriodAndExpiresWhatWasLeft() throws Exception {
        String sub = sub();
        String cus = cus();
        Instant paid = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Account account = subscribed(sub, cus, paid, daysFromNow(1));
        // 1,000 of the 6,000 credits were used.
        ledgerUsage(account.id(), "1.000");

        Instant renewedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant nextEnd = daysFromNow(31);
        String renewal = stripeInvoicePaid(newEventId(), renewedAt, sub, cus, null, null, nextEnd);
        stripe(renewal).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);
        assertThat(lines(account.id(), "PLAN_EXPIRY")).isEqualTo(1);
        // No rollover by default: the 5,000 left expired, then 6,000 arrived.
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
        assertThat(((java.sql.Timestamp) subscriptionRow(account.id()).get("current_period_end")).toInstant())
                .isEqualTo(nextEnd);

        // The same period again under another event id (a retried invoice): nothing more is granted.
        stripe(stripeInvoicePaid(newEventId(), renewedAt.plusSeconds(1), sub, cus, null, null, nextEnd))
                .andExpect(status().isOk());
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void aFailedPaymentIsPastDueThenBackToFreeAfterTheGracePeriodAndTheRemoteSubscriptionIsStopped()
            throws Exception {
        String sub = sub();
        String cus = cus();
        providers().stubFor(delete(urlPathEqualTo("/v1/subscriptions/" + sub)).willReturn(ok("{}")));
        Instant paid = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Account account = subscribed(sub, cus, paid, Instant.now().plusSeconds(3600));

        stripe(stripeInvoiceFailed(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                sub, cus)).andExpect(status().isOk());

        assertThat(subscriptionStatus(account.id())).isEqualTo("PAST_DUE");
        // Still on the paid plan during the grace period: the job leaves it alone.
        jobs.expire(Instant.now().plusSeconds(86_400));
        assertThat(subscriptionStatus(account.id())).isEqualTo("PAST_DUE");

        // Grace is 3 days by default: 4 days later it is over.
        jobs.expire(Instant.now().plusSeconds(4 * 86_400L));
        assertThat(subscriptionStatus(account.id())).isEqualTo("CANCELED");
        providers().verify(deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/" + sub)));
        // Credits already granted stay, and the user is on Free again: the free grant works.
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
        assertThat(grants.grantFreeIfDue(account.id(), Instant.now().plusSeconds(4 * 86_400L))).isTrue();
    }

    @Test
    void aLaterPaymentBringsAPastDueSubscriptionBack() throws Exception {
        String sub = sub();
        String cus = cus();
        Instant paid = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Account account = subscribed(sub, cus, paid, Instant.now().plusSeconds(3600));
        stripe(stripeInvoiceFailed(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                sub, cus)).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("PAST_DUE");

        stripe(stripeInvoicePaid(newEventId(), Instant.now().plusSeconds(5).truncatedTo(
                java.time.temporal.ChronoUnit.SECONDS), sub, cus, null, null, daysFromNow(31)))
                .andExpect(status().isOk());

        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        assertThat(subscriptionRow(account.id()).get("past_due_since")).isNull();
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);
    }

    @Test
    void cancellingKeepsThePlanUntilThePeriodEndsThenTheUserIsOnFree() throws Exception {
        String sub = sub();
        String cus = cus();
        Instant paid = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant end = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Account account = subscribed(sub, cus, paid, end);

        stripe(stripeSubscriptionUpdated(newEventId(), Instant.now().truncatedTo(
                java.time.temporal.ChronoUnit.SECONDS), sub, cus, true)).andExpect(status().isOk());

        Map<String, Object> row = subscriptionRow(account.id());
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("cancel_at_period_end", true);
        jobs.expire(end.minusSeconds(10));
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");

        jobs.expire(end.plusSeconds(10));
        assertThat(subscriptionStatus(account.id())).isEqualTo("CANCELED");
        // The user stopped it themselves, so it is already stopped at Stripe: no further call.
        providers().verify(0, deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/" + sub)));
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void theSubscriptionEndingAtStripeMovesTheUserToFreeAtOnce() throws Exception {
        String sub = sub();
        String cus = cus();
        Account account = subscribed(sub, cus, Instant.now().minusSeconds(60).truncatedTo(
                java.time.temporal.ChronoUnit.SECONDS), daysFromNow(10));

        stripe(stripeSubscriptionDeleted(newEventId(), Instant.now().plusSeconds(2).truncatedTo(
                java.time.temporal.ChronoUnit.SECONDS), sub, cus)).andExpect(status().isOk());

        assertThat(subscriptionStatus(account.id())).isEqualTo("CANCELED");
        assertThat(subscriptions.live(account.id())).isEmpty();
    }

    @Test
    void aDuplicateDeliveryChangesNothing() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        String body = stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                sub(), cus(), account.id(), "pro", daysFromNow(30));

        stripe(body).andExpect(status().isOk());
        int events = webhookEvents("STRIPE");
        stripe(body).andExpect(status().isOk());
        stripe(body).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
        assertThat(webhookEvents("STRIPE")).isEqualTo(events);
        assertThat(count("select count(*) from subscriptions where user_id = ?", account.id())).isEqualTo(1);
    }

    @Test
    void aBadSignatureIsRefusedWithNoDetailAndChangesNothing() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        String body = stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                sub(), cus(), account.id(), "pro", daysFromNow(30));
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        int events = webhookEvents("STRIPE");

        // Wrong secret, no header, a header that is not a signature, a signature over a different body, a timestamp
        // outside the tolerance (a replayed capture): every one is a bare 400.
        stripe(body, stripeSignature(raw, now, "whsec_some_other_secret")).andExpect(status().isBadRequest())
                .andExpect(content().string(""));
        stripe(body, null).andExpect(status().isBadRequest()).andExpect(content().string(""));
        stripe(body, "garbage").andExpect(status().isBadRequest()).andExpect(content().string(""));
        stripe(body, stripeSignature("{\"id\":\"other\"}".getBytes(StandardCharsets.UTF_8), now,
                PaymentFixtures.STRIPE_WEBHOOK_SECRET)).andExpect(status().isBadRequest());
        stripe(body, stripeSignature(raw, now - 3600, PaymentFixtures.STRIPE_WEBHOOK_SECRET))
                .andExpect(status().isBadRequest());

        assertThat(webhookEvents("STRIPE")).isEqualTo(events);
        assertThat(lines(account.id(), "PLAN_GRANT")).isZero();
        assertThat(subscriptionStatus(account.id())).isEqualTo("PENDING");
        // Any one of several v1 signatures may match (secret rotation).
        String good = stripeSignature(raw, now, PaymentFixtures.STRIPE_WEBHOOK_SECRET);
        stripe(body, good + ",v1=" + "0".repeat(64)).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
    }

    @Test
    void aReplayedOrOutOfOrderEventNeverRegressesTheSubscription() throws Exception {
        String sub = sub();
        String cus = cus();
        Instant first = Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant firstEnd = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Account account = newAccount();
        pending(account.id(), "STRIPE");

        // The renewal (period 2) is delivered BEFORE the first payment (period 1).
        Instant renewedAt = Instant.now().minusSeconds(3000).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant secondEnd = daysFromNow(30);
        stripe(stripeInvoicePaid(newEventId(), renewedAt, sub, cus, account.id(), "pro", secondEnd))
                .andExpect(status().isOk());
        stripe(stripeInvoicePaid(newEventId(), first, sub, cus, account.id(), "pro", firstEnd))
                .andExpect(status().isOk());

        Map<String, Object> row = subscriptionRow(account.id());
        assertThat(row).containsEntry("status", "ACTIVE");
        assertThat(((java.sql.Timestamp) row.get("current_period_end")).toInstant()).isEqualTo(secondEnd);
        // Both periods were paid, so both are credited, each once.
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);

        // A payment failure from BEFORE the renewal, delivered late, does not make it past due.
        stripe(stripeInvoiceFailed(newEventId(), renewedAt.minusSeconds(500), sub, cus)).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");

        // A cancel-at-period-end flag older than what is recorded is dropped; a newer one is applied.
        stripe(stripeSubscriptionUpdated(newEventId(), renewedAt.minusSeconds(400), sub, cus, true))
                .andExpect(status().isOk());
        assertThat(subscriptionRow(account.id())).containsEntry("cancel_at_period_end", false);
        stripe(stripeSubscriptionUpdated(newEventId(), renewedAt.plusSeconds(400), sub, cus, true))
                .andExpect(status().isOk());
        assertThat(subscriptionRow(account.id())).containsEntry("cancel_at_period_end", true);

        // An "ended" event older than the newest applied one is stale: the subscription stays.
        stripe(stripeSubscriptionDeleted(newEventId(), renewedAt.minusSeconds(100), sub, cus))
                .andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        // The same payment event replayed under a new id changes no state and grants nothing more.
        stripe(stripeInvoicePaid(newEventId(), renewedAt.minusSeconds(2000), sub, cus, null, null, secondEnd))
                .andExpect(status().isOk());
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
    }

    @Test
    void aPaymentThatCannotBeMatchedYetAsksForARetryAndIsNotMarkedHandled() throws Exception {
        String sub = sub();
        String cus = cus();
        String body = stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                sub, cus, null, null, daysFromNow(30));
        int events = webhookEvents("STRIPE");

        stripe(body).andExpect(status().isServiceUnavailable());
        assertThat(webhookEvents("STRIPE")).isEqualTo(events);

        // Its companion arrives, and the redelivery is processed afresh.
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        stripe(stripeSubscriptionCheckout(newEventId(), Instant.now(), sub, cus, account.id(), "pro"))
                .andExpect(status().isOk());
        stripe(body).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void anUnknownEventTypeIsAcknowledgedCountedAndNotStored() throws Exception {
        double before = ignoredCount();
        int events = webhookEvents("STRIPE");

        stripe(stripeEvent(newEventId(), "customer.created", Instant.now(), "{\"id\":\"cus_1\"}"))
                .andExpect(status().isOk());

        assertThat(ignoredCount()).isEqualTo(before + 1);
        assertThat(webhookEvents("STRIPE")).isEqualTo(events);
    }

    private double ignoredCount() {
        var counter = meters.find("billing.webhooks").tags("provider", "stripe", "outcome", "ignored").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aTopUpIsCreditedExactlyOnceAndNeverExpires() throws Exception {
        Account account = newAccount();
        String intent = "pi_" + UUID.randomUUID().toString().substring(0, 12);
        String body = stripePackCheckout(newEventId(), Instant.now(), intent, account.id(), "pack_small");

        stripe(body).andExpect(status().isOk());
        stripe(body).andExpect(status().isOk());
        // Another event id for the same payment (Stripe may send both completed and async_payment_succeeded).
        stripe(stripePackCheckout(newEventId(), Instant.now(), intent, account.id(), "pack_small"))
                .andExpect(status().isOk());

        assertThat(lines(account.id(), "TOPUP")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("select topup_after from credit_ledger where user_id = ? and reason = 'TOPUP'",
                java.math.BigDecimal.class, account.id())).isEqualByComparingTo("2000");

        // The month turns: the free grant arrives and top-up credits are not expired.
        grants.grantFreeIfDue(account.id(), Instant.now());
        assertThat(lines(account.id(), "PLAN_EXPIRY")).isZero();
        assertThat(balance(account.id())).isEqualByComparingTo("2300");
        // Next month: the unspent free credits expire, the top-up credits stay.
        grants.grantFreeIfDue(account.id(), Instant.now().plus(java.time.Duration.ofDays(40)));
        assertThat(lines(account.id(), "PLAN_EXPIRY")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("2300");
        assertThat(jdbc.queryForObject("select topup_after from credit_ledger where user_id = ? order by id desc "
                + "limit 1", java.math.BigDecimal.class, account.id())).isEqualByComparingTo("2000");
    }

    @Test
    void aSecondPaidSubscriptionForTheSameUserIsIgnored() throws Exception {
        String sub = sub();
        Account account = subscribed(sub, cus(), Instant.now().minusSeconds(60).truncatedTo(
                java.time.temporal.ChronoUnit.SECONDS), daysFromNow(10));

        stripe(stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                sub(), cus(), account.id(), "pro", daysFromNow(30))).andExpect(status().isOk());

        assertThat(count("select count(*) from subscriptions where user_id = ?", account.id())).isEqualTo(1);
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
    }

    /** Debits the user's ledger as AI usage would ({@code costUsd} dollars; 1,000 credits per dollar). */
    private void ledgerUsage(UUID user, String costUsd) {
        usageLedger.record(usage(newKey(), user, "parse_resume", costUsd));
    }

    @Autowired
    private LedgerService usageLedger;
}
