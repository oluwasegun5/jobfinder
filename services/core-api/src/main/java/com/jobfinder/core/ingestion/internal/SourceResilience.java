package com.jobfinder.core.ingestion.internal;

import java.time.Duration;
import java.util.function.Supplier;

import com.jobfinder.core.ingestion.SourceFetchException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Fault tolerance for one source, shared by all its targets and all its runs (so the circuit breaker
 * remembers across runs). Layers, outermost first:
 *
 * <ol>
 * <li><b>circuit breaker</b>: when most recent targets failed with a retryable error, further
 * targets fail fast for a while instead of hammering a source that is down. It counts one outcome
 * per target (after retries), and only retryable failures count against it: a permanent failure,
 * such as a 404 for one retired board, says nothing about the source's health;
 * <li><b>retry</b> with exponential backoff, for retryable failures only;
 * <li><b>rate limiter</b>, evaluated before every attempt including retries, which blocks (up to a
 * timeout) rather than failing.
 * </ol>
 *
 * Built once per source from its settings; changing a source's tuning takes effect on restart.
 */
final class SourceResilience {

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RateLimiter rateLimiter;

    SourceResilience(String sourceCode, SourceSettings settings, IngestionProperties.Defaults defaults) {
        this.circuitBreaker = CircuitBreaker.of("ingestion-" + sourceCode, CircuitBreakerConfig.custom()
                .failureRateThreshold(defaults.breakerFailureRateThreshold())
                .minimumNumberOfCalls(defaults.breakerMinimumCalls())
                .slidingWindowSize(Math.max(10, defaults.breakerMinimumCalls()))
                .waitDurationInOpenState(defaults.breakerOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(SourceResilience::isRetryable)
                .build());
        this.retry = Retry.of("ingestion-" + sourceCode, RetryConfig.custom()
                .maxAttempts(settings.retryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(defaults.retryInitialBackoff(), 2.0))
                .retryOnException(SourceResilience::isRetryable)
                .build());
        this.rateLimiter = RateLimiter.of("ingestion-" + sourceCode, RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofNanos(Math.round(1_000_000_000d / settings.requestsPerSecond())))
                .timeoutDuration(defaults.rateLimitTimeout())
                .build());
    }

    <T> T call(Supplier<T> fetch) {
        Supplier<T> limited = RateLimiter.decorateSupplier(rateLimiter, fetch);
        Supplier<T> retried = Retry.decorateSupplier(retry, limited);
        return CircuitBreaker.decorateSupplier(circuitBreaker, retried).get();
    }

    CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    private static boolean isRetryable(Throwable t) {
        return t instanceof SourceFetchException e && e.retryable();
    }
}
