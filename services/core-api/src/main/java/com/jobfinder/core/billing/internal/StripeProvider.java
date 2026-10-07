package com.jobfinder.core.billing.internal;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.jobfinder.core.billing.internal.BillingProperties.Pack;
import com.jobfinder.core.billing.internal.BillingProperties.Price;
import com.jobfinder.core.billing.internal.PlanCatalog.Plan;
import com.jobfinder.core.billing.internal.ProviderEvent.Kind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe over plain HTTP: a Checkout Session (hosted page) for a subscription or a one-off pack, subscription
 * cancellation, and webhook verification (docs/adr/0036-plans-credits-and-payments.md). No Stripe SDK: three calls and
 * one signature scheme do not justify it. The API key comes from {@code app.billing.stripe.secret-key}, the signing
 * secret from {@code app.billing.stripe.webhook-secret}, both from the environment.
 *
 * <p><b>Signature.</b> {@code Stripe-Signature: t=<unix seconds>,v1=<hex>[,v1=<hex>...]}, where v1 is
 * HMAC-SHA256 over {@code t + "." + rawBody} with the signing secret. The timestamp must be within the configured
 * tolerance of now (a captured request cannot be replayed later); any v1 may match (secret rotation).
 */
@Component
class StripeProvider implements PaymentProvider {

    private final BillingProperties.Stripe config;
    private final JsonMapper json;
    private final RestClient client;

    StripeProvider(BillingProperties properties, JsonMapper json) {
        this.config = properties.stripe();
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(config.timeout()).build());
        factory.setReadTimeout(config.timeout());
        this.client = RestClient.builder().baseUrl(config.apiBase()).requestFactory(factory).build();
    }

    @Override
    public Provider provider() {
        return Provider.STRIPE;
    }

    @Override
    public CheckoutLink checkoutPlan(CheckoutContext context, Plan plan, String currency, Price price) {
        MultiValueMap<String, String> form = baseForm(context);
        form.add("mode", "subscription");
        form.add("line_items[0][price]", price.providerPlanId());
        form.add("line_items[0][quantity]", "1");
        meta(form, "metadata", context, KIND_PLAN, plan.code());
        meta(form, "subscription_data[metadata]", context, KIND_PLAN, plan.code());
        return createSession(form);
    }

    @Override
    public CheckoutLink checkoutPack(CheckoutContext context, Pack pack, String currency, Price price) {
        MultiValueMap<String, String> form = baseForm(context);
        form.add("mode", "payment");
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", currency.toLowerCase(java.util.Locale.ROOT));
        form.add("line_items[0][price_data][unit_amount]", Long.toString(price.amountMinor()));
        form.add("line_items[0][price_data][product_data][name]", pack.name() == null ? pack.id() : pack.name());
        meta(form, "metadata", context, KIND_PACK, pack.id());
        meta(form, "payment_intent_data[metadata]", context, KIND_PACK, pack.id());
        return createSession(form);
    }

    private MultiValueMap<String, String> baseForm(CheckoutContext context) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("success_url", context.returnUrl());
        form.add("cancel_url", context.cancelUrl());
        form.add("customer_email", context.email());
        form.add("client_reference_id", context.reference());
        return form;
    }

    private static void meta(MultiValueMap<String, String> form, String prefix, CheckoutContext context, String kind,
            String item) {
        form.add(prefix + "[" + META_USER + "]", context.userId().toString());
        form.add(prefix + "[" + META_KIND + "]", kind);
        form.add(prefix + "[" + META_ITEM + "]", item);
    }

    private CheckoutLink createSession(MultiValueMap<String, String> form) {
        try {
            JsonNode response = json.readTree(client.post().uri("/v1/checkout/sessions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.secretKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class));
            String url = Jn.text(response, "url");
            if (url == null) {
                throw new PaymentProviderException("Stripe returned no checkout url", null);
            }
            return new CheckoutLink(url);
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            throw new PaymentProviderException("Stripe checkout request failed", e);
        }
    }

    @Override
    public void cancelAtPeriodEnd(String providerRef) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("cancel_at_period_end", "true");
        try {
            client.post().uri("/v1/subscriptions/{id}", providerRef)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.secretKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // Already gone at Stripe: nothing left to stop.
        } catch (RestClientException e) {
            throw new PaymentProviderException("Stripe cancel request failed", e);
        }
    }

    @Override
    public void cancelNow(String providerRef) {
        try {
            client.delete().uri("/v1/subscriptions/{id}", providerRef)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.secretKey()).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // Already gone at Stripe.
        } catch (RestClientException e) {
            throw new PaymentProviderException("Stripe cancel request failed", e);
        }
    }

    @Override
    public ProviderEvent verifyAndParse(byte[] rawBody, Map<String, String> headers, Instant now) {
        verifySignature(rawBody, headers.get("stripe-signature"), now);
        JsonNode event;
        try {
            event = json.readTree(rawBody);
        } catch (tools.jackson.core.JacksonException e) {
            throw new InvalidWebhookException("unreadable body");
        }
        String id = Jn.text(event, "id");
        String type = Jn.text(event, "type");
        JsonNode object = Jn.at(event, "data", "object");
        if (id == null || type == null || object == null) {
            throw new InvalidWebhookException("not an event");
        }
        Instant at = Jn.epochSeconds(event, "created");
        return map(id, type, at == null ? now : at, object);
    }

    private void verifySignature(byte[] rawBody, String header, Instant now) {
        String secret = config.webhookSecret();
        if (secret == null || secret.isBlank() || header == null) {
            throw new InvalidWebhookException("no secret or signature");
        }
        long timestamp = -1;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    throw new InvalidWebhookException("bad timestamp");
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp < 0 || signatures.isEmpty()) {
            throw new InvalidWebhookException("malformed signature header");
        }
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + rawBody.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(rawBody, 0, signed, prefix.length, rawBody.length);
        String expected = Hmac.hex("HmacSHA256", secret, signed);
        boolean match = false;
        for (String presented : signatures) {
            match |= Hmac.matches(expected, presented);
        }
        if (!match) {
            throw new InvalidWebhookException("signature mismatch");
        }
        Duration age = Duration.between(Instant.ofEpochSecond(timestamp), now).abs();
        if (age.compareTo(config.webhookTolerance()) > 0) {
            throw new InvalidWebhookException("timestamp outside tolerance");
        }
    }

    private ProviderEvent map(String id, String type, Instant at, JsonNode object) {
        switch (type) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                return checkoutCompleted(id, type, at, object);
            }
            case "invoice.paid" -> {
                Instant periodEnd = Jn.epochSeconds(object, "lines", "data", "0", "period", "end");
                if (periodEnd == null) {
                    periodEnd = Jn.epochSeconds(object, "period_end");
                }
                String subscription = firstText(object, new String[] { "subscription" },
                        new String[] { "parent", "subscription_details", "subscription" });
                if (subscription == null || periodEnd == null) {
                    return ProviderEvent.ignored(Provider.STRIPE, id, type, at);
                }
                JsonNode meta = subscriptionMetadata(object);
                // Before tax and after discounts: what the catalog price must equal. Falls back to what was paid.
                Long paid = Jn.number(object, "total_excluding_tax");
                if (paid == null) {
                    paid = Jn.number(object, "amount_paid");
                }
                return new ProviderEvent(Provider.STRIPE, id, type, Kind.PLAN_PAID, at,
                        Jn.uuid(Jn.text(meta, META_USER)), Jn.text(meta, META_ITEM), null, subscription,
                        Jn.text(object, "customer"), Jn.text(object, "id"), periodEnd, null, paid,
                        Jn.text(object, "currency"));
            }
            case "invoice.payment_failed" -> {
                String subscription = firstText(object, new String[] { "subscription" },
                        new String[] { "parent", "subscription_details", "subscription" });
                if (subscription == null) {
                    return ProviderEvent.ignored(Provider.STRIPE, id, type, at);
                }
                return new ProviderEvent(Provider.STRIPE, id, type, Kind.PAYMENT_FAILED, at, null, null, null,
                        subscription, Jn.text(object, "customer"), null, null, null, null, null);
            }
            case "customer.subscription.updated", "customer.subscription.deleted" -> {
                String status = Jn.text(object, "status");
                boolean ended = type.endsWith(".deleted") || "canceled".equals(status)
                        || "incomplete_expired".equals(status);
                Instant periodEnd = Jn.epochSeconds(object, "current_period_end");
                if (periodEnd == null) {
                    periodEnd = Jn.epochSeconds(object, "items", "data", "0", "current_period_end");
                }
                boolean cancelScheduled = Boolean.TRUE.equals(Jn.bool(object, "cancel_at_period_end"))
                        || Jn.at(object, "cancel_at") != null;
                return new ProviderEvent(Provider.STRIPE, id, type,
                        ended ? Kind.SUBSCRIPTION_ENDED : Kind.SUBSCRIPTION_UPDATED, at, null, null, null,
                        Jn.text(object, "id"), Jn.text(object, "customer"), null, periodEnd, cancelScheduled, null,
                        null);
            }
            default -> {
                if (type.equals("charge.refunded") || type.startsWith("charge.dispute.")) {
                    return new ProviderEvent(Provider.STRIPE, id, type, Kind.REFUND_OR_DISPUTE, at,
                            Jn.uuid(Jn.text(object, "metadata", META_USER)), null, null, null, null, null, null, null,
                            null, null);
                }
                return ProviderEvent.ignored(Provider.STRIPE, id, type, at);
            }
        }
    }

    private ProviderEvent checkoutCompleted(String id, String type, Instant at, JsonNode session) {
        JsonNode meta = Jn.at(session, "metadata");
        UUID user = Jn.uuid(Jn.text(meta, META_USER));
        String mode = Jn.text(session, "mode");
        if ("payment".equals(mode) && "paid".equals(Jn.text(session, "payment_status"))
                && KIND_PACK.equals(Jn.text(meta, META_KIND))) {
            String payment = Jn.text(session, "payment_intent");
            // The total less tax: the pack's catalog price, whatever tax the provider added on top.
            Long total = Jn.number(session, "amount_total");
            Long tax = Jn.number(session, "total_details", "amount_tax");
            Long net = total == null ? null : total - (tax == null ? 0 : tax);
            return new ProviderEvent(Provider.STRIPE, id, type, Kind.TOPUP_PAID, at, user, null,
                    Jn.text(meta, META_ITEM), null, Jn.text(session, "customer"),
                    payment == null ? Jn.text(session, "id") : payment, null, null, net,
                    Jn.text(session, "currency"));
        }
        if ("subscription".equals(mode) && Jn.text(session, "subscription") != null) {
            return new ProviderEvent(Provider.STRIPE, id, type, Kind.SUBSCRIPTION_LINKED, at, user,
                    Jn.text(meta, META_ITEM), null, Jn.text(session, "subscription"), Jn.text(session, "customer"),
                    null, null, null, null, null);
        }
        return ProviderEvent.ignored(Provider.STRIPE, id, type, at);
    }

    /** Where Stripe puts the subscription's metadata on an invoice depends on the API version: try each place. */
    private static JsonNode subscriptionMetadata(JsonNode invoice) {
        for (String[] path : new String[][] { { "parent", "subscription_details", "metadata" },
                { "subscription_details", "metadata" }, { "lines", "data", "0", "metadata" }, { "metadata" } }) {
            JsonNode meta = Jn.at(invoice, path);
            if (meta != null && Jn.text(meta, META_USER) != null) {
                return meta;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String[]... paths) {
        for (String[] path : paths) {
            String value = Jn.text(node, path);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
