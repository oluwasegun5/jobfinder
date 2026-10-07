package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Billing ({@code app.billing.*}, docs/adr/0025-ai-usage-ledger.md and docs/adr/0036-plans-credits-and-payments.md).
 * Nothing about money is in code: credits, prices, provider plan ids, packs, keys and secrets all come from here
 * (secrets from the environment only).
 *
 * @param dailyCapCredits    the most credits a user's AI calls may consume per UTC day; 0 turns the cap off
 * @param microUsdPerCredit  how many millionths of a dollar one credit stands for (1000: a credit is a tenth of a
 *                           cent); the credit price of a call is its provider cost divided by this
 * @param rolloverCapCredits how many unused plan credits survive into the next monthly grant; 0 (the default) means
 *                           no rollover. Top-up credits never expire and are not subject to this.
 * @param gracePeriod        how long a subscription may stay PAST_DUE, and how long an ACTIVE one may go without its
 *                           renewal being seen, before the user is moved back to Free
 * @param plans              per plan code: the monthly credits (when set, overrides the seeded {@code plans} row) and
 *                           the prices
 * @param packs              one-off credit packs
 */
@ConfigurationProperties("app.billing")
record BillingProperties(@DefaultValue("500") BigDecimal dailyCapCredits,
        @DefaultValue("1000") long microUsdPerCredit,
        @DefaultValue("0") BigDecimal rolloverCapCredits,
        @DefaultValue("3d") Duration gracePeriod,
        @DefaultValue Jobs jobs,
        @DefaultValue Checkout checkout,
        @DefaultValue Limits limits,
        @DefaultValue Map<String, PlanConfig> plans,
        @DefaultValue List<Pack> packs,
        @DefaultValue Stripe stripe,
        @DefaultValue Paystack paystack) {

    BillingProperties {
        if (dailyCapCredits == null || dailyCapCredits.signum() < 0) {
            throw new IllegalArgumentException("app.billing.daily-cap-credits must be 0 (no cap) or more");
        }
        if (microUsdPerCredit < 1) {
            throw new IllegalArgumentException("app.billing.micro-usd-per-credit must be at least 1");
        }
        if (rolloverCapCredits == null || rolloverCapCredits.signum() < 0) {
            throw new IllegalArgumentException("app.billing.rollover-cap-credits must be 0 (no rollover) or more");
        }
        if (gracePeriod == null || gracePeriod.isNegative()) {
            throw new IllegalArgumentException("app.billing.grace-period must not be negative");
        }
    }

    boolean capEnabled() {
        return dailyCapCredits.signum() > 0;
    }

    /** The amount-minor the shipped catalogue uses as a stand-in; no real price may equal it in production. */
    static final long PLACEHOLDER_AMOUNT_MINOR = 100;

    /**
     * Whether {@code provider} has a key set (the secret key; Stripe's webhook secret alone does not count), that is,
     * whether real money could move through it.
     */
    boolean providerKeySet(Provider provider) {
        String key = switch (provider) {
            case STRIPE -> stripe.secretKey();
            case PAYSTACK -> paystack.secretKey();
        };
        return key != null && !key.isBlank();
    }

    /**
     * What is still a placeholder in the prices of every provider that has a key: an amount of
     * {@link #PLACEHOLDER_AMOUNT_MINOR} or a provider plan id containing {@code PLACEHOLDER}. Empty when the catalogue
     * is fit to take real payments. {@link BillingPriceGuard} refuses to start the prod profile on a non-empty list.
     */
    List<String> placeholderPrices() {
        List<String> problems = new ArrayList<>();
        plans.forEach((code, plan) -> plan.prices()
                .forEach((currency, price) -> check(problems, "plan " + code + " " + currency, price)));
        for (Pack pack : packs) {
            pack.prices().forEach((currency, price) -> check(problems, "pack " + pack.id() + " " + currency, price));
        }
        return problems;
    }

    private void check(List<String> problems, String what, Price price) {
        if (!providerKeySet(price.provider())) {
            return;
        }
        if (price.amountMinor() == PLACEHOLDER_AMOUNT_MINOR) {
            problems.add(what + " (" + price.provider().slug() + "): amount-minor is the placeholder "
                    + PLACEHOLDER_AMOUNT_MINOR);
        }
        if (price.providerPlanId() != null
                && price.providerPlanId().toUpperCase(java.util.Locale.ROOT).contains("PLACEHOLDER")) {
            problems.add(what + " (" + price.provider().slug() + "): provider-plan-id is a placeholder");
        }
    }

    Optional<PlanConfig> plan(String code) {
        return Optional.ofNullable(plans.get(code));
    }

    Optional<Pack> pack(String id) {
        return packs.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** A price in minor units (cents, kobo) of one currency, charged by one provider. */
    record Price(Provider provider, long amountMinor, String providerPlanId) {
        Price {
            if (provider == null || amountMinor < 1) {
                throw new IllegalArgumentException("A price needs a provider and a positive amount-minor");
            }
        }
    }

    /** {@code monthlyCredits} null: use the seeded plans row. {@code prices} is keyed by ISO currency code. */
    record PlanConfig(BigDecimal monthlyCredits, @DefaultValue Map<String, Price> prices) {
    }

    record Pack(String id, String name, BigDecimal credits, @DefaultValue Map<String, Price> prices) {
        Pack {
            if (id == null || id.isBlank() || credits == null || credits.signum() <= 0) {
                throw new IllegalArgumentException("A credit pack needs an id and positive credits");
            }
        }
    }

    /** The scheduled jobs. Both are safe to run as often as you like; the cron only sets how soon a miss is fixed. */
    record Jobs(@DefaultValue("true") boolean enabled,
            @DefaultValue("0 10 0 * * *") String freeGrantCron,
            @DefaultValue("0 0 * * * *") String expiryCron,
            @DefaultValue("UTC") String zone,
            @DefaultValue("500") int batchSize,
            @DefaultValue("10m") Duration lockAtMostFor) {
    }

    /** Where the hosted checkout sends the browser back to (our web app). */
    record Checkout(@DefaultValue("http://localhost:3000/billing/return") String returnUrl,
            @DefaultValue("http://localhost:3000/billing/return?canceled=1") String cancelUrl) {
    }

    /** Rate limits of the user-facing write endpoints (per user). */
    record Limits(@DefaultValue("10") int checkoutPerHour, @DefaultValue("10") int cancelPerHour) {
    }

    /** {@code secretKey} and {@code webhookSecret} come from the environment; blank means Stripe is not offered. */
    record Stripe(@DefaultValue("https://api.stripe.com") String apiBase, String secretKey, String webhookSecret,
            @DefaultValue("5m") Duration webhookTolerance, @DefaultValue("10s") Duration timeout) {
        boolean configured() {
            return secretKey != null && !secretKey.isBlank() && webhookSecret != null && !webhookSecret.isBlank();
        }
    }

    /** {@code secretKey} is both the API key and the webhook signing key (Paystack signs with it). */
    record Paystack(@DefaultValue("https://api.paystack.co") String apiBase, String secretKey,
            @DefaultValue("10s") Duration timeout) {
        boolean configured() {
            return secretKey != null && !secretKey.isBlank();
        }
    }
}
