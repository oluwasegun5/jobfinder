package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI billing ({@code app.billing.*}, docs/adr/0025-ai-usage-ledger.md).
 *
 * @param dailyCapCredits  the most credits a user's AI calls may consume per UTC day; 0 turns the cap off
 * @param microUsdPerCredit how many millionths of a dollar one credit stands for (1000: a credit is a tenth of a
 *                          cent); the credit price of a call is its provider cost divided by this
 */
@ConfigurationProperties("app.billing")
record BillingProperties(@DefaultValue("500") BigDecimal dailyCapCredits,
        @DefaultValue("1000") long microUsdPerCredit) {

    BillingProperties {
        if (dailyCapCredits == null || dailyCapCredits.signum() < 0) {
            throw new IllegalArgumentException("app.billing.daily-cap-credits must be 0 (no cap) or more");
        }
        if (microUsdPerCredit < 1) {
            throw new IllegalArgumentException("app.billing.micro-usd-per-credit must be at least 1");
        }
    }

    boolean capEnabled() {
        return dailyCapCredits.signum() > 0;
    }
}
