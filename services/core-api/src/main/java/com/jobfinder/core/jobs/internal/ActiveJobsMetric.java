package com.jobfinder.core.jobs.internal;

import java.time.Duration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.observability.CachedGauge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/** PLAN.md section 11, active jobs: postings currently ACTIVE, read at most once a minute. */
@Component
class ActiveJobsMetric {

    ActiveJobsMetric(MeterRegistry meters, JdbcClient jdbc) {
        CachedGauge.register(meters, "jobs.active", "Jobs currently active", Tags.empty(), Duration.ofMinutes(1),
                () -> jdbc.sql("select count(*) from jobs where status = 'ACTIVE'").query(Long.class).single());
    }
}
