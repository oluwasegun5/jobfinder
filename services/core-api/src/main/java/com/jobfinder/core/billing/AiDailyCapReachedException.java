package com.jobfinder.core.billing;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpStatus;

/**
 * The user has used up today's AI allowance. HTTP 429 with the stable code {@code ai_daily_cap_reached}, a
 * {@code resetsAt} member (ISO-8601 UTC) in the problem document and a {@code Retry-After} header.
 */
public class AiDailyCapReachedException extends AiAllowanceException {

    public static final String CODE = "ai_daily_cap_reached";

    private final Instant resetsAt;

    public AiDailyCapReachedException(Instant resetsAt, Instant now) {
        super(HttpStatus.TOO_MANY_REQUESTS, CODE,
                "You have reached today's AI usage limit. It resets at " + resetsAt + ".");
        this.resetsAt = resetsAt;
        withProperty("resetsAt", resetsAt.toString());
        withHeader("Retry-After", Long.toString(Math.max(1, Duration.between(now, resetsAt).toSeconds())));
    }

    public Instant resetsAt() {
        return resetsAt;
    }
}
