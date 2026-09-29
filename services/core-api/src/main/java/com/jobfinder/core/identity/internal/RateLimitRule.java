package com.jobfinder.core.identity.internal;

import java.time.Duration;

/**
 * One rate-limited action, keyed either by client IP or by (hashed) email. Defaults can be
 * overridden per rule under {@code app.rate-limit.rules.<rule-name>}.
 */
enum RateLimitRule {
    SIGNUP_IP(5, Duration.ofHours(1)),
    LOGIN_IP(20, Duration.ofMinutes(15)),
    LOGIN_EMAIL(10, Duration.ofMinutes(15)),
    REFRESH_IP(60, Duration.ofMinutes(1)),
    VERIFY_EMAIL_IP(10, Duration.ofHours(1)),
    RESEND_VERIFICATION_IP(5, Duration.ofHours(1)),
    RESEND_VERIFICATION_EMAIL(3, Duration.ofHours(1)),
    FORGOT_PASSWORD_IP(5, Duration.ofHours(1)),
    FORGOT_PASSWORD_EMAIL(3, Duration.ofHours(1)),
    RESET_PASSWORD_IP(10, Duration.ofHours(1));

    private final int defaultCapacity;
    private final Duration defaultPeriod;

    RateLimitRule(int defaultCapacity, Duration defaultPeriod) {
        this.defaultCapacity = defaultCapacity;
        this.defaultPeriod = defaultPeriod;
    }

    int defaultCapacity() {
        return defaultCapacity;
    }

    Duration defaultPeriod() {
        return defaultPeriod;
    }
}
