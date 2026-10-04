package com.jobfinder.core.identity.internal;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import jakarta.validation.constraints.NotBlank;

/**
 * Configuration under {@code app.rate-limit}: {@code rules} for the auth endpoints ({@link RateLimitRule}) and
 * {@code endpoints} for every other handler ({@link EndpointClass}). Production defaults live in the two enums; a deployment
 * or a test can override a single class, for example
 * {@code app.rate-limit.endpoints.AI.capacity=5} and {@code app.rate-limit.endpoints.AI.period=1m}.
 */
@ConfigurationProperties("app.rate-limit")
record RateLimitProperties(
        @DefaultValue("redis://localhost:6379") @NotBlank String redisUri,
        @DefaultValue Map<RateLimitRule, Limit> rules,
        @DefaultValue Map<EndpointClass, Limit> endpoints) {

    record Limit(int capacity, Duration period) {
    }

    Limit limitFor(RateLimitRule rule) {
        Limit override = rules.get(rule);
        return override != null ? override : new Limit(rule.defaultCapacity(), rule.defaultPeriod());
    }

    Limit endpointLimit(EndpointClass endpointClass) {
        Limit override = endpoints.get(endpointClass);
        return override != null ? override : new Limit(endpointClass.defaultCapacity(), endpointClass.defaultPeriod());
    }
}
