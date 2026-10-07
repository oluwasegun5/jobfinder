package com.jobfinder.core.identity;

import java.time.Duration;

/**
 * Token-bucket rate limiting for other modules' endpoints, on the same Redis-backed buckets login uses. Fails closed
 * like the login limits do: if the bucket store cannot be reached the request is refused.
 */
public interface RateLimits {

    /**
     * Consumes one token from the bucket {@code name} for {@code subject} (a user id or a hashed identifier, never a
     * raw email).
     *
     * @param capacity how many actions the bucket allows per {@code period}
     * @throws com.jobfinder.core.shared.ApiException 429 {@code rate_limited} (with {@code Retry-After}) when the
     *                                                bucket is empty, 503 {@code rate_limiter_unavailable} when the
     *                                                store cannot be reached
     */
    void check(String name, String subject, int capacity, Duration period);
}
