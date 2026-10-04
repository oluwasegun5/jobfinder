package com.jobfinder.core.billing.internal;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
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
 * Paystack over plain HTTP: initialize-transaction (the hosted page) for a subscription or a one-off pack,
 * subscription disabling, and webhook verification (docs/adr/0036-plans-credits-and-payments.md). No SDK.
 *
 * <p><b>Signature.</b> {@code x-paystack-signature} is HMAC-SHA512 over the RAW body with the account's secret key,
 * as lowercase hex. Paystack sends no timestamp, so a replay cannot be told apart by age; it is made harmless by the
 * delivery being idempotent instead.
 *
 * <p><b>Event ids.</b> Paystack events carry no id. The id used for deduplication is the SHA-256 of the raw body,
 * which a redelivery repeats exactly. Event time is the payload's {@code paid_at}/{@code created_at}, else the
 * moment of receipt.
 *
 * <p><b>Periods.</b> Plans are monthly: the period a successful charge covers ends one calendar month after
 * {@code paid_at}. A first payment is matched to its user by the metadata we sent; a renewal (which carries no
 * metadata) by the Paystack customer code stored on the subscription.
 */
@Component
class PaystackProvider implements PaymentProvider {

    private final BillingProperties.Paystack config;
    private final JsonMapper json;
    private final RestClient client;

    PaystackProvider(BillingProperties properties, JsonMapper json) {
        this.config = properties.paystack();
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(config.timeout()).build());
        factory.setReadTimeout(config.timeout());
        this.client = RestClient.builder().baseUrl(config.apiBase()).requestFactory(factory).build();
    }

    @Override
    public Provider provider() {
        return Provider.PAYSTACK;
    }

    @Override
    public CheckoutLink checkoutPlan(CheckoutContext context, Plan plan, String currency, Price price) {
        Map<String, Object> body = base(context, currency, price);
        body.put("plan", price.providerPlanId());
        body.put("metadata", metadata(context, KIND_PLAN, plan.code()));
        return initialize(body);
    }

    @Override
    public CheckoutLink checkoutPack(CheckoutContext context, Pack pack, String currency, Price price) {
        Map<String, Object> body = base(context, currency, price);
        body.put("metadata", metadata(context, KIND_PACK, pack.id()));
        return initialize(body);
    }

    private static Map<String, Object> base(CheckoutContext context, String currency, Price price) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", context.email());
        body.put("amount", price.amountMinor());
        body.put("currency", currency.toUpperCase(java.util.Locale.ROOT));
        body.put("reference", context.reference());
        body.put("callback_url", context.returnUrl());
        return body;
    }

    private static Map<String, Object> metadata(CheckoutContext context, String kind, String item) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(META_USER, context.userId().toString());
        meta.put(META_KIND, kind);
        meta.put(META_ITEM, item);
        return meta;
    }

    private CheckoutLink initialize(Map<String, Object> body) {
        try {
            JsonNode response = json.readTree(client.post().uri("/transaction/initialize")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.secretKey())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
            String url = Jn.text(response, "data", "authorization_url");
            if (url == null || !Boolean.TRUE.equals(Jn.bool(response, "status"))) {
                throw new PaymentProviderException("Paystack returned no authorization url", null);
            }
            return new CheckoutLink(url);
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            throw new PaymentProviderException("Paystack initialize request failed", e);
        }
    }

    /** Paystack has no cancel-at-period-end: disabling stops future charges, and we keep access until the period ends. */
    @Override
    public void cancelAtPeriodEnd(String providerRef) {
        disable(providerRef);
    }

    @Override
    public void cancelNow(String providerRef) {
        disable(providerRef);
    }

    private void disable(String code) {
        try {
            JsonNode subscription = json.readTree(client.get().uri("/subscription/{code}", code)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.secretKey()).retrieve()
                    .body(String.class));
            String token = Jn.text(subscription, "data", "email_token");
            if (token == null) {
                throw new PaymentProviderException("Paystack returned no email token", null);
            }
            client.post().uri("/subscription/disable").header(HttpHeaders.AUTHORIZATION, "Bearer " + config.secretKey())
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("code", code, "token", token)).retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // Unknown at Paystack: nothing left to stop.
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            throw new PaymentProviderException("Paystack disable request failed", e);
        }
    }

    @Override
    public ProviderEvent verifyAndParse(byte[] rawBody, Map<String, String> headers, Instant now) {
        String secret = config.secretKey();
        String presented = headers.get("x-paystack-signature");
        if (secret == null || secret.isBlank() || presented == null
                || !Hmac.matches(Hmac.hex("HmacSHA512", secret, rawBody), presented)) {
            throw new InvalidWebhookException("signature mismatch");
        }
        JsonNode event;
        try {
            event = json.readTree(rawBody);
        } catch (tools.jackson.core.JacksonException e) {
            throw new InvalidWebhookException("unreadable body");
        }
        String type = Jn.text(event, "event");
        JsonNode data = Jn.at(event, "data");
        if (type == null || data == null) {
            throw new InvalidWebhookException("not an event");
        }
        String id = Hmac.sha256Hex(rawBody);
        Instant at = firstInstant(data, "paid_at", "created_at", "createdAt");
        return map(id, type, at == null ? now : at, data);
    }

    private ProviderEvent map(String id, String type, Instant at, JsonNode data) {
        String customer = Jn.text(data, "customer", "customer_code");
        switch (type) {
            case "charge.success" -> {
                if (!"success".equals(Jn.text(data, "status"))) {
                    return ProviderEvent.ignored(Provider.PAYSTACK, id, type, at);
                }
                JsonNode meta = metadataOf(data);
                String reference = Jn.text(data, "reference");
                String kind = Jn.text(meta, META_KIND);
                if (KIND_PACK.equals(kind) && reference != null) {
                    return new ProviderEvent(Provider.PAYSTACK, id, type, Kind.TOPUP_PAID, at,
                            Jn.uuid(Jn.text(meta, META_USER)), null, Jn.text(meta, META_ITEM), null, customer,
                            reference, null, null);
                }
                String planCode = KIND_PLAN.equals(kind) ? Jn.text(meta, META_ITEM) : null;
                boolean planCharge = planCode != null || Jn.text(data, "plan", "plan_code") != null;
                if (!planCharge || reference == null) {
                    return ProviderEvent.ignored(Provider.PAYSTACK, id, type, at);
                }
                Instant periodEnd = at.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
                return new ProviderEvent(Provider.PAYSTACK, id, type, Kind.PLAN_PAID, at,
                        Jn.uuid(Jn.text(meta, META_USER)), planCode, null, null, customer, reference, periodEnd,
                        null);
            }
            case "subscription.create" -> {
                String code = Jn.text(data, "subscription_code");
                if (code == null) {
                    return ProviderEvent.ignored(Provider.PAYSTACK, id, type, at);
                }
                return new ProviderEvent(Provider.PAYSTACK, id, type, Kind.SUBSCRIPTION_LINKED, at, null, null, null,
                        code, customer, null, firstInstant(data, "next_payment_date"), null);
            }
            case "invoice.payment_failed" -> {
                String code = Jn.text(data, "subscription", "subscription_code");
                if (code == null && customer == null) {
                    return ProviderEvent.ignored(Provider.PAYSTACK, id, type, at);
                }
                return new ProviderEvent(Provider.PAYSTACK, id, type, Kind.PAYMENT_FAILED, at, null, null, null, code,
                        customer, null, null, null);
            }
            case "subscription.not_renew", "subscription.disable" -> {
                String code = Jn.text(data, "subscription_code");
                if (code == null) {
                    return ProviderEvent.ignored(Provider.PAYSTACK, id, type, at);
                }
                return new ProviderEvent(Provider.PAYSTACK, id, type, Kind.SUBSCRIPTION_UPDATED, at, null, null, null,
                        code, customer, null, null, Boolean.TRUE);
            }
            default -> {
                return ProviderEvent.ignored(Provider.PAYSTACK, id, type, at);
            }
        }
    }

    /** The metadata we sent, as an object (Paystack may echo it as an object or as a JSON string). */
    private JsonNode metadataOf(JsonNode data) {
        JsonNode meta = Jn.at(data, "metadata");
        if (meta != null && meta.isString()) {
            try {
                meta = json.readTree(meta.asString());
            } catch (tools.jackson.core.JacksonException e) {
                return null;
            }
        }
        return meta != null && meta.isObject() ? meta : null;
    }

    private static Instant firstInstant(JsonNode data, String... names) {
        for (String name : names) {
            String text = Jn.text(data, name);
            if (text != null) {
                try {
                    return Instant.parse(text);
                } catch (java.time.format.DateTimeParseException e) {
                    try {
                        return java.time.OffsetDateTime.parse(text).toInstant();
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // try the next name
                    }
                }
            }
        }
        return null;
    }
}
