package com.jobfinder.core.billing.internal;

import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Production must not charge placeholder prices: under the {@code prod} profile the application refuses to start when
 * a provider has a key and any of its configured prices still carries the shipped stand-in amount or a provider plan
 * id containing {@code PLACEHOLDER} (docs/adr/0036-plans-credits-and-payments.md). Development and test are not
 * affected, and neither is a provider without keys (it is never offered).
 */
@Component
class BillingPriceGuard {

    BillingPriceGuard(BillingProperties properties, Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> problems = properties.placeholderPrices();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with placeholder billing prices under the prod "
                    + "profile; set the real amounts and provider plan ids (app.billing.plans / app.billing.packs): "
                    + String.join("; ", problems));
        }
    }
}
