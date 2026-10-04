package com.jobfinder.core.billing.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jobfinder.core.PaymentFixtures;
import com.jobfinder.core.TestcontainersConfiguration.PaymentProviderMock;

/**
 * Plumbing for the payment tests: signed Stripe and Paystack webhook deliveries (built with the fake secrets from
 * {@link PaymentFixtures}, nothing real), the provider API stub, and reading the subscription and ledger rows.
 */
abstract class PaymentTestSupport extends BillingTestSupport {

    @Autowired
    private PaymentProviderMock providerMock;

    @Autowired
    protected CreditGrants grants;

    @Autowired
    protected BillingJobs jobs;

    @Autowired
    protected PlanCatalog plans;

    @Autowired
    protected SubscriptionStore subscriptions;

    @Autowired
    protected DailyCapService gate;

    @Autowired
    protected RemoteCancellations remoteCancellations;

    /** A signed-in user: the id and the access token. */
    protected record Account(UUID id, String token) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    protected WireMockServer providers() {
        return providerMock.server();
    }

    protected Account newAccount() throws Exception {
        String email = registerVerifiedUser();
        UUID id = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        return new Account(id, login(email, PASSWORD, newIp()).accessToken());
    }

    // ---- Stripe ---------------------------------------------------------------------------------------------

    protected static String stripeSignature(byte[] body, long timestamp, String secret) {
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return "t=" + timestamp + ",v1=" + Hmac.hex("HmacSHA256", secret, signed);
    }

    /** Delivers a Stripe event signed now with the right secret. */
    protected ResultActions stripe(String body) throws Exception {
        long now = Instant.now().getEpochSecond();
        return stripe(body, stripeSignature(body.getBytes(StandardCharsets.UTF_8), now,
                PaymentFixtures.STRIPE_WEBHOOK_SECRET));
    }

    protected ResultActions stripe(String body, String signatureHeader) throws Exception {
        var request = post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (signatureHeader != null) {
            request = request.header("Stripe-Signature", signatureHeader);
        }
        return mvc.perform(request);
    }

    protected static String stripeEvent(String id, String type, Instant created, String objectJson) {
        return """
                {"id":"%s","object":"event","type":"%s","created":%d,"data":{"object":%s}}"""
                .formatted(id, type, created.getEpochSecond(), objectJson);
    }

    protected static String newEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    protected static String stripeInvoicePaid(String eventId, Instant at, String subscription, String customer,
            UUID user, String plan, Instant periodEnd) {
        String meta = user == null ? "{}" : "{\"jf_user\":\"%s\",\"jf_kind\":\"plan\",\"jf_item\":\"%s\"}"
                .formatted(user, plan);
        return stripeEvent(eventId, "invoice.paid", at, """
                {"id":"in_%s","object":"invoice","customer":"%s","subscription":"%s","amount_paid":100,
                 "period_end":%d,
                 "parent":{"subscription_details":{"subscription":"%s","metadata":%s}},
                 "lines":{"data":[{"period":{"start":%d,"end":%d}}]}}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), customer, subscription,
                        at.getEpochSecond(), subscription, meta, at.getEpochSecond(), periodEnd.getEpochSecond()));
    }

    protected static String stripeInvoiceFailed(String eventId, Instant at, String subscription, String customer) {
        return stripeEvent(eventId, "invoice.payment_failed", at, """
                {"id":"in_%s","object":"invoice","customer":"%s","subscription":"%s"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), customer, subscription));
    }

    protected static String stripeSubscriptionUpdated(String eventId, Instant at, String subscription,
            String customer, boolean cancelAtPeriodEnd) {
        return stripeEvent(eventId, "customer.subscription.updated", at, """
                {"id":"%s","object":"subscription","customer":"%s","status":"active","cancel_at_period_end":%s}"""
                .formatted(subscription, customer, cancelAtPeriodEnd));
    }

    protected static String stripeSubscriptionDeleted(String eventId, Instant at, String subscription,
            String customer) {
        return stripeEvent(eventId, "customer.subscription.deleted", at, """
                {"id":"%s","object":"subscription","customer":"%s","status":"canceled"}"""
                .formatted(subscription, customer));
    }

    protected static String stripeSubscriptionCheckout(String eventId, Instant at, String subscription,
            String customer, UUID user, String plan) {
        return stripeEvent(eventId, "checkout.session.completed", at, """
                {"id":"cs_%s","object":"checkout.session","mode":"subscription","payment_status":"paid",
                 "customer":"%s","subscription":"%s",
                 "metadata":{"jf_user":"%s","jf_kind":"plan","jf_item":"%s"}}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), customer, subscription, user, plan));
    }

    protected static String stripePackCheckout(String eventId, Instant at, String paymentIntent, UUID user,
            String pack) {
        return stripeEvent(eventId, "checkout.session.completed", at, """
                {"id":"cs_%s","object":"checkout.session","mode":"payment","payment_status":"paid",
                 "customer":null,"payment_intent":"%s",
                 "metadata":{"jf_user":"%s","jf_kind":"pack","jf_item":"%s"}}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), paymentIntent, user, pack));
    }

    // ---- Paystack -------------------------------------------------------------------------------------------

    protected static String paystackSignature(String body) {
        return Hmac.hex("HmacSHA512", PaymentFixtures.PAYSTACK_KEY, body.getBytes(StandardCharsets.UTF_8));
    }

    protected ResultActions paystack(String body) throws Exception {
        return paystack(body, paystackSignature(body));
    }

    protected ResultActions paystack(String body, String signature) throws Exception {
        var request = post("/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            request = request.header("x-paystack-signature", signature);
        }
        return mvc.perform(request);
    }

    protected static String paystackChargePlan(UUID user, String plan, String reference, String customer,
            Instant paidAt) {
        return """
                {"event":"charge.success","data":{"id":%d,"status":"success","reference":"%s","paid_at":"%s",
                 "amount":100,"currency":"NGN","customer":{"customer_code":"%s"},
                 "plan":{"plan_code":"PLN_PLACEHOLDER_pro_ngn"},
                 "metadata":{"jf_user":"%s","jf_kind":"plan","jf_item":"%s"}}}"""
                .formatted(Math.abs(reference.hashCode()), reference, paidAt, customer, user, plan);
    }

    /** A renewal: Paystack sends no metadata, only the customer and the plan. */
    protected static String paystackChargeRenewal(String reference, String customer, Instant paidAt) {
        return """
                {"event":"charge.success","data":{"id":%d,"status":"success","reference":"%s","paid_at":"%s",
                 "amount":100,"currency":"NGN","customer":{"customer_code":"%s"},
                 "plan":{"plan_code":"PLN_PLACEHOLDER_pro_ngn"},"metadata":null}}"""
                .formatted(Math.abs(reference.hashCode()), reference, paidAt, customer);
    }

    protected static String paystackChargePack(UUID user, String pack, String reference, Instant paidAt) {
        return """
                {"event":"charge.success","data":{"id":%d,"status":"success","reference":"%s","paid_at":"%s",
                 "amount":100,"currency":"NGN","customer":{"customer_code":"CUS_pack"},"plan":{},
                 "metadata":{"jf_user":"%s","jf_kind":"pack","jf_item":"%s"}}}"""
                .formatted(Math.abs(reference.hashCode()), reference, paidAt, user, pack);
    }

    protected static String paystackSubscriptionCreate(String code, String customer, Instant next) {
        return """
                {"event":"subscription.create","data":{"subscription_code":"%s","status":"active",
                 "next_payment_date":"%s","customer":{"customer_code":"%s"},
                 "plan":{"plan_code":"PLN_PLACEHOLDER_pro_ngn"}}}""".formatted(code, next, customer);
    }

    protected static String paystackPaymentFailed(String code, String customer, Instant at) {
        return """
                {"event":"invoice.payment_failed","data":{"invoice_code":"INV_%s","created_at":"%s",
                 "subscription":{"subscription_code":"%s"},"customer":{"customer_code":"%s"}}}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), at, code, customer);
    }

    protected static String paystackNotRenew(String code, String customer, Instant at) {
        return """
                {"event":"subscription.not_renew","data":{"subscription_code":"%s","status":"non-renewing",
                 "createdAt":"%s","customer":{"customer_code":"%s"}}}""".formatted(code, at, customer);
    }

    // ---- rows -----------------------------------------------------------------------------------------------

    protected Map<String, Object> subscriptionRow(UUID user) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from subscriptions where user_id = ? order by created_at desc", user);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected String subscriptionStatus(UUID user) {
        Map<String, Object> row = subscriptionRow(user);
        return row == null ? null : (String) row.get("status");
    }

    protected int lines(UUID user, String reason) {
        return count("select count(*) from credit_ledger where user_id = ? and reason = ?", user, reason);
    }

    protected int webhookEvents(String provider) {
        return count("select count(*) from webhook_events where provider = ?", provider);
    }

    /** Starts a subscription checkout row for the user, as POST /billing/checkout does. */
    protected void pending(UUID user, String provider) {
        subscriptions.startPending(user, plans.byCode("pro").orElseThrow().id(), Provider.valueOf(provider),
                Instant.now());
    }

    protected static Instant daysFromNow(long days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }
}
