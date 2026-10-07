package com.jobfinder.core.applications.internal;

import java.time.Duration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.observability.CachedGauge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * The funnel stage "applied": users with at least one application that left SAVED.
 * Read from the database (a gauge, so it survives restarts and covers every instance), at most once a minute.
 * Grafana divides the stages for the conversion rates (PLAN.md section 11).
 */
@Component
class AppliedUsersMetric {

    AppliedUsersMetric(MeterRegistry meters, JdbcClient jdbc) {
        CachedGauge.register(meters, "funnel.users", "Users that reached a stage of the signup funnel",
                Tags.of("stage", "applied"), Duration.ofMinutes(1),
                () -> jdbc.sql("select count(distinct user_id) from applications where applied_at is not null").query(Long.class).single());
    }
}
