package com.jobfinder.core.billing.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.billing.internal.PaymentProvider.InvalidWebhookException;
import com.jobfinder.core.billing.internal.ProviderEvent.Kind;
import com.jobfinder.core.billing.internal.SubscriptionService.EventNotResolvableException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Handles one webhook delivery (docs/adr/0036-plans-credits-and-payments.md): verify the signature over the raw body,
 * then, in ONE transaction, claim the event in {@code webhook_events} (UNIQUE (provider, event_id)) and apply it. A
 * duplicate delivery finds the claim and changes nothing; a delivery that fails rolls the claim back with everything
 * else, so the provider's retry is processed afresh. Event types billing does not act on are acknowledged, counted in
 * the {@code billing.webhooks} metric and not stored. Only the event id and type are ever logged, never the payload.
 */
@Service
class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    enum Result { PROCESSED, DUPLICATE, IGNORED }

    private final Map<Provider, PaymentProvider> providers = new EnumMap<>(Provider.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SubscriptionService subscriptions;
    private final MeterRegistry meters;
    private final Clock clock;

    WebhookService(java.util.List<PaymentProvider> providers, JdbcClient jdbc, TransactionTemplate tx,
            SubscriptionService subscriptions, MeterRegistry meters, Clock clock) {
        providers.forEach(p -> this.providers.put(p.provider(), p));
        this.jdbc = jdbc;
        this.tx = tx;
        this.subscriptions = subscriptions;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @throws InvalidWebhookException       the signature is wrong or the body is not an event (answer 400, no detail)
     * @throws EventNotResolvableException   the event needs one that has not arrived yet (answer 5xx so it is retried)
     */
    Result handle(Provider provider, byte[] rawBody, Map<String, String> headers) {
        Instant now = Instant.now(clock);
        ProviderEvent event;
        try {
            event = providers.get(provider).verifyAndParse(rawBody, headers, now);
        } catch (InvalidWebhookException e) {
            count(provider, "rejected");
            log.warn("Webhook rejected: provider={} reason={}", provider.slug(), e.getMessage());
            throw e;
        }
        if (event.kind() == Kind.IGNORED) {
            count(provider, "ignored");
            log.debug("Webhook ignored: provider={} id={} type={}", provider.slug(), event.id(), event.type());
            return Result.IGNORED;
        }
        try {
            Result result = tx.execute(status -> {
                if (!claim(event, now)) {
                    return Result.DUPLICATE;
                }
                subscriptions.apply(event, now);
                return Result.PROCESSED;
            });
            count(provider, result == Result.DUPLICATE ? "duplicate" : "processed");
            log.info("Webhook {}: provider={} id={} type={}", result.name().toLowerCase(), provider.slug(), event.id(),
                    event.type());
            return result;
        } catch (EventNotResolvableException e) {
            count(provider, "retry");
            log.warn("Webhook cannot be matched yet, asking for a retry: provider={} id={} type={}", provider.slug(),
                    event.id(), event.type());
            throw e;
        }
    }

    private boolean claim(ProviderEvent event, Instant now) {
        return jdbc.sql("""
                insert into webhook_events (provider, event_id, event_type, received_at)
                values (:provider, :id, :type, :at)
                on conflict (provider, event_id) do nothing
                """)
                .param("provider", event.provider().name()).param("id", event.id())
                .param("type", truncate(event.type())).param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update() == 1;
    }

    private static String truncate(String type) {
        return type.length() > 100 ? type.substring(0, 100) : type;
    }

    private void count(Provider provider, String outcome) {
        meters.counter("billing.webhooks", "provider", provider.slug(), "outcome", outcome).increment();
    }
}
