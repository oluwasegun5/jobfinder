package com.jobfinder.core.observability;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * A gauge whose value comes from a query. Prometheus scrapes every few seconds; the query runs at most once per
 * {@code ttl}, and a failing query reports NaN (no sample) instead of breaking the scrape.
 */
public final class CachedGauge {

    private static final Logger log = LoggerFactory.getLogger(CachedGauge.class);

    private final Supplier<? extends Number> source;
    private final long ttlNanos;
    private long loadedAt;
    private double value = Double.NaN;
    private boolean loaded;

    private CachedGauge(Supplier<? extends Number> source, Duration ttl) {
        this.source = source;
        this.ttlNanos = ttl.toNanos();
    }

    public static void register(MeterRegistry registry, String name, String description, Tags tags, Duration ttl,
            Supplier<? extends Number> source) {
        CachedGauge cached = new CachedGauge(source, ttl);
        // Micrometer holds the state object of a gauge weakly; nothing else references this one.
        Gauge.builder(name, cached, CachedGauge::get).strongReference(true).description(description).tags(tags)
                .register(registry);
    }

    private synchronized double get() {
        long now = System.nanoTime();
        if (!loaded || now - loadedAt >= ttlNanos) {
            loaded = true;
            loadedAt = now;
            try {
                Number number = source.get();
                value = number == null ? Double.NaN : number.doubleValue();
            } catch (RuntimeException e) {
                log.warn("Gauge query failed: {}", e.getClass().getSimpleName());
                value = Double.NaN;
            }
        }
        return value;
    }
}
