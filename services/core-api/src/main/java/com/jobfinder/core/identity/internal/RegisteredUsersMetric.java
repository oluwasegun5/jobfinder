package com.jobfinder.core.identity.internal;

import java.time.Duration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.observability.CachedGauge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * The first stage of the funnel: accounts that exist.
 * Read from the database (a gauge, so it survives restarts and covers every instance), at most once a minute.
 * Grafana divides the stages for the conversion rates (PLAN.md section 11).
 */
@Component
class RegisteredUsersMetric {

    RegisteredUsersMetric(MeterRegistry meters, JdbcClient jdbc) {
        CachedGauge.register(meters, "funnel.users", "Users that reached a stage of the signup funnel",
                Tags.of("stage", "registered"), Duration.ofMinutes(1),
                () -> jdbc.sql("select count(*) from users").query(Long.class).single());
    }
}
