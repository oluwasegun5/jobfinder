package com.jobfinder.core.identity.internal;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import jakarta.validation.constraints.NotBlank;

/** Configuration under {@code app.rate-limit}. */
@ConfigurationProperties("app.rate-limit")
record RateLimitProperties(
        @DefaultValue("redis://localhost:6379") @NotBlank String redisUri,
        @DefaultValue Map<RateLimitRule, Limit> rules) {

    record Limit(int capacity, Duration period) {
    }

    Limit limitFor(RateLimitRule rule) {
        Limit override = rules.get(rule);
        return override != null ? override : new Limit(rule.defaultCapacity(), rule.defaultPeriod());
    }
}
