package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionProperties;
import com.jobfinder.core.compliance.RetentionTask;

/** Raw job postings are kept for reprocessing only: deleted once the last fetch is older than the retention period. */
@Component
class RawPostingRetention implements RetentionTask {

    private final JdbcClient jdbc;
    private final RetentionProperties properties;

    RawPostingRetention(JdbcClient jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "raw-postings";
    }

    @Override
    public int purge(Instant now) {
        return jdbc.sql("delete from raw_job_postings where fetched_at < :cutoff")
                .param("cutoff", now.minus(properties.rawPostings()).atOffset(ZoneOffset.UTC)).update();
    }
}
