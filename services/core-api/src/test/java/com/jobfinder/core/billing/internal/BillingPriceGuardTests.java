package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Production must not start with placeholder prices. These tests load the shipped application.yml (and
 * application-prod.yml) with only the guard and the properties, so what is checked is the real catalogue, with every
 * key and secret fake.
 */
class BillingPriceGuardTests {

    private static final String[] REAL_PRICES = { "BILLING_PRICE_PRO_USD_MINOR=1500",
            "BILLING_PRICE_PRO_NGN_MINOR=2500000", "BILLING_PRICE_PACK_SMALL_USD_MINOR=499",
            "BILLING_PRICE_PACK_SMALL_NGN_MINOR=800000", "BILLING_PRICE_PACK_LARGE_USD_MINOR=1999",
            "BILLING_PRICE_PACK_LARGE_NGN_MINOR=3000000", "STRIPE_PRICE_PRO_USD=price_1Realprousd",
            "PAYSTACK_PLAN_PRO_NGN=PLN_realpro" };

    private static String[] concat(String[] first, String... more) {
        String[] all = new String[first.length + more.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(more, 0, all, first.length, more.length);
        return all;
    }

    private ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(BillingConfig.class, BillingPriceGuard.class).withPropertyValues(properties);
    }

    @Test
    void prodWithAProviderKeyAndTheShippedPlaceholdersRefusesToStart() {
        runner("spring.profiles.active=prod", "STRIPE_SECRET_KEY=sk_test_FAKE", "STRIPE_WEBHOOK_SECRET=whsec_FAKE")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("placeholder").hasMessageContaining("plan pro USD (stripe)")
                            .hasMessageContaining("pack pack_small USD (stripe)")
                            // Only the provider that has a key is checked.
                            .hasMessageNotContaining("(paystack)");
                });
        runner("spring.profiles.active=prod", "PAYSTACK_SECRET_KEY=sk_test_FAKE").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("plan pro NGN (paystack)")
                    .hasMessageNotContaining("(stripe)");
        });
    }

    @Test
    void aRealAmountWithAPlaceholderPlanIdStillRefusesToStart() {
        String[] properties = concat(REAL_PRICES, "spring.profiles.active=prod", "STRIPE_SECRET_KEY=sk_test_FAKE",
                "STRIPE_PRICE_PRO_USD=price_PLACEHOLDER_pro_usd");
        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("provider-plan-id")
                    .hasMessageNotContaining("amount-minor");
        });
    }

    @Test
    void aPlaceholderAmountWithARealPlanIdRefusesToStart() {
        String[] properties = concat(REAL_PRICES, "spring.profiles.active=prod", "PAYSTACK_SECRET_KEY=sk_test_FAKE",
                "BILLING_PRICE_PACK_LARGE_NGN_MINOR=100");
        runner(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("pack pack_large NGN (paystack)");
        });
    }

    @Test
    void prodWithRealPricesStartsForOneOrBothProviders() {
        runner(concat(REAL_PRICES, "spring.profiles.active=prod", "STRIPE_SECRET_KEY=sk_test_FAKE",
                "PAYSTACK_SECRET_KEY=sk_test_FAKE")).run(context -> assertThat(context).hasNotFailed());
        runner(concat(REAL_PRICES, "spring.profiles.active=prod", "PAYSTACK_SECRET_KEY=sk_test_FAKE"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodWithoutProviderKeysStartsEvenWithPlaceholders() {
        // Nothing is offered without keys, so nothing can be charged.
        runner("spring.profiles.active=prod").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void developmentAndTestAreNotAffectedByPlaceholders() {
        runner("STRIPE_SECRET_KEY=sk_test_FAKE", "PAYSTACK_SECRET_KEY=sk_test_FAKE")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
