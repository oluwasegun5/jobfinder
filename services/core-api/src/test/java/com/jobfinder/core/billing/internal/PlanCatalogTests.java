package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.billing.internal.BillingProperties.Pack;
import com.jobfinder.core.billing.internal.BillingProperties.PlanConfig;
import com.jobfinder.core.billing.internal.BillingProperties.Price;

/** A provider is offered only when it has keys, so a deployment without Stripe or Paystack credentials never shows it. */
class PlanCatalogTests {

    private static BillingProperties properties(String stripeKey, String stripeWebhook, String paystackKey) {
        Map<String, Price> prices = Map.of("USD", new Price(Provider.STRIPE, 100, "price_x"), "NGN",
                new Price(Provider.PAYSTACK, 100, "PLN_x"));
        return new BillingProperties(new BigDecimal("500"), 1000, BigDecimal.ZERO, Duration.ofDays(3),
                new BillingProperties.Jobs(false, false, "0 10 0 * * *", "0 0 * * * *", "0 20 * * * *", "UTC", 10,
                        Duration.ofMinutes(5)),
                new BillingProperties.Checkout("http://r", "http://c"), new BillingProperties.Limits(10, 10),
                Map.of("pro", new PlanConfig(null, prices)),
                List.of(new Pack("p", "P", BigDecimal.TEN, prices)),
                new BillingProperties.Stripe("http://s", stripeKey, stripeWebhook, Duration.ofMinutes(5),
                        Duration.ofSeconds(5)),
                new BillingProperties.Paystack("http://p", paystackKey, Duration.ofSeconds(5)),
                new BillingProperties.Webhooks(Duration.ofHours(24)));
    }

    @Test
    void aProviderWithoutKeysIsNotOffered() {
        PlanCatalog none = new PlanCatalog(null, properties("", "", ""));
        assertThat(none.providerConfigured(Provider.STRIPE)).isFalse();
        assertThat(none.providerConfigured(Provider.PAYSTACK)).isFalse();

        // Stripe needs both its API key and its webhook signing secret: a key alone could take payments we cannot hear of.
        assertThat(new PlanCatalog(null, properties("sk", "", "")).providerConfigured(Provider.STRIPE)).isFalse();
        assertThat(new PlanCatalog(null, properties("sk", "whsec", "")).providerConfigured(Provider.STRIPE)).isTrue();
        assertThat(new PlanCatalog(null, properties("", "", "pk")).providerConfigured(Provider.PAYSTACK)).isTrue();
    }

    @Test
    void aPriceThatIsNotFullyConfiguredIsDroppedRatherThanOffered() {
        // A plan price needs an amount and the provider's plan id; a pack price only an amount (it is ad hoc).
        PlanConfig plan = new PlanConfig(null, Map.of("USD", new Price(Provider.STRIPE, 0, "price_x"),
                "NGN", new Price(Provider.PAYSTACK, 450_000, " ")));
        assertThat(plan.prices()).isEmpty();
        PlanConfig ok = new PlanConfig(null, Map.of("USD", new Price(Provider.STRIPE, 500, "price_x")));
        assertThat(ok.prices()).containsOnlyKeys("USD");

        Pack pack = new Pack("p", "P", BigDecimal.TEN, Map.of("USD", new Price(Provider.STRIPE, 0, null),
                "NGN", new Price(Provider.PAYSTACK, 800_000, null)));
        assertThat(pack.prices()).containsOnlyKeys("NGN");
    }

    @Test
    void anItemIsOfferedOnlyForAConfiguredProviderThatHasAPriceForIt() {
        PlanCatalog catalog = new PlanCatalog(null, properties("sk", "whsec", ""));
        Pack pack = properties("", "", "").packs().get(0);

        assertThat(catalog.offered(pack, Provider.STRIPE)).isPresent();
        assertThat(catalog.offered(pack, Provider.STRIPE).get().getKey()).isEqualTo("USD");
        assertThat(catalog.offered(pack, Provider.PAYSTACK)).isEmpty();
    }
}
