package com.jobfinder.core.billing.internal;

import java.util.Locale;
import java.util.Optional;

/** The payment providers. Stripe takes USD, Paystack NGN (the prices in configuration say which). */
enum Provider {
    STRIPE, PAYSTACK;

    /** The lowercase path segment and metric tag: {@code stripe}, {@code paystack}. */
    String slug() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<Provider> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Provider p : values()) {
            if (p.name().equalsIgnoreCase(value.trim())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
