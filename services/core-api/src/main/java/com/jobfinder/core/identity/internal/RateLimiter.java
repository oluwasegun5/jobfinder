package com.jobfinder.core.identity.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.RateLimits;

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
class RateLimiter implements RateLimits, DisposableBean {

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
        consume(rule.name(), subject, limit.capacity(), limit.period());
    }

    /** For other modules' endpoints: the same bucket logic with the limit given by the caller. */
    @Override
    public void check(String name, String subject, int capacity, Duration period) {
        consume("ext:" + name, subject, capacity, period);
    }

    private void consume(String bucketName, String subject, int capacityPerPeriod, Duration period) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacityPerPeriod)
                        .refillGreedy(capacityPerPeriod, period)
                        .build())
                .build();
        byte[] key = ("rl:" + bucketName + ":" + subject).getBytes(StandardCharsets.UTF_8);

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

    /**
     * Erases every bucket kept for a signed-in user (keys {@code rl:*:<user id>}), so no Redis key outlives the account.
     * Best effort by design: the buckets hold only counters under an opaque id and expire on their own within the hour,
     * so an unreachable Redis must not block the deletion; it is logged and the keys age out.
     *
     * @return how many keys were removed
     */
    int forgetSubject(UUID userId) {
        try {
            proxyManager();
            int removed = 0;
            try (var connection = client.connect()) {
                var commands = connection.sync();
                var args = io.lettuce.core.ScanArgs.Builder.matches("rl:*:" + userId).limit(500);
                io.lettuce.core.KeyScanCursor<String> cursor = commands.scan(args);
                while (true) {
                    if (!cursor.getKeys().isEmpty()) {
                        removed += commands.del(cursor.getKeys().toArray(String[]::new)).intValue();
                    }
                    if (cursor.isFinished()) {
                        return removed;
                    }
                    cursor = commands.scan(cursor, args);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not erase the rate-limit keys of a deleted account; they expire on their own", e);
            return 0;
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
