package com.jobfinder.core.identity.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;

/**
 * Token-bucket rate limiting shared across instances through Redis (bucket4j).
 * Fails closed: if Redis cannot be reached the request is rejected with 503 rather than
 * letting an unthrottled login endpoint be hammered.
 *
 * <p>The Redis connection is opened lazily so the application can start (and its health
 * probe pass) before Redis is reachable.
 */
@Component
class RateLimiter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final RateLimitProperties properties;
    private RedisClient client;
    private LettuceBasedProxyManager<byte[]> proxyManager;

    RateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    /**
     * Consumes one token from the bucket for {@code rule} and {@code subject}.
     *
     * @param subject client IP, or an already-hashed identifier; never a raw email
     * @throws AuthException 429 when the bucket is empty, 503 when Redis is unavailable
     */
    void check(RateLimitRule rule, String subject) {
        RateLimitProperties.Limit limit = properties.limitFor(rule);
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period())
                        .build())
                .build();
        byte[] key = ("rl:" + rule.name() + ":" + subject).getBytes(StandardCharsets.UTF_8);

        ConsumptionProbe probe;
        try {
            probe = proxyManager().builder().build(key, () -> configuration).tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            log.error("Rate limiter backend unavailable; rejecting request", e);
            throw AuthException.rateLimiterUnavailable();
        }
        if (!probe.isConsumed()) {
            long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
            throw AuthException.rateLimited(seconds);
        }
    }

    private synchronized LettuceBasedProxyManager<byte[]> proxyManager() {
        if (proxyManager == null) {
            client = RedisClient.create(properties.redisUri());
            proxyManager = LettuceBasedProxyManager.builderFor(client)
                    // Idle buckets disappear from Redis once they would have fully refilled.
                    .withExpirationStrategy(
                            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(5)))
                    .build();
        }
        return proxyManager;
    }

    @Override
    public synchronized void destroy() {
        if (client != null) {
            client.shutdown();
        }
    }
}
