package com.jobfinder.core.ingestion;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.jobfinder.core.TestcontainersConfiguration;

/**
 * Shared context for the ingestion tests: real Postgres, three fake sources, and a scheduler that
 * is enabled but never ticks on its own (an hour's poll interval), so tests drive runs themselves.
 * Tuned for speed: tiny retry backoff, no real rate limiting, and a breaker that opens after
 * three failed calls.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, IngestionTestConfig.class })
@TestPropertySource(properties = {
        "app.ingestion.scheduler.poll-interval-ms=3600000",
        "app.ingestion.scheduler.initial-delay-ms=3600000",
        "app.ingestion.defaults.retry-initial-backoff=10ms",
        "app.ingestion.defaults.requests-per-second=1000",
        "app.ingestion.defaults.breaker-minimum-calls=3" })
public abstract class IngestionTestSupport {

    protected static final String FAKE = "FAKE";
    protected static final String FAKE_RETRY = "FAKE_RETRY";
    protected static final String FAKE_BREAKER = "FAKE_BREAKER";

    @Autowired
    protected IngestionService ingestion;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    @Qualifier("fakeSource")
    protected FakeJobSourceAdapter fake;

    @Autowired
    @Qualifier("fakeRetrySource")
    protected FakeJobSourceAdapter fakeRetry;

    @Autowired
    @Qualifier("fakeBreakerSource")
    protected FakeJobSourceAdapter fakeBreaker;

    @BeforeEach
    protected void cleanSlate() {
        for (String code : new String[] { FAKE, FAKE_RETRY, FAKE_BREAKER }) {
            jdbc.update("delete from raw_job_postings where source_id = (select id from sources where code = ?)", code);
            jdbc.update("delete from ingestion_runs where source_id = (select id from sources where code = ?)", code);
            jdbc.update("delete from source_targets where source_id = (select id from sources where code = ?)", code);
            jdbc.update("update sources set enabled = true, config = '{}'::jsonb, last_run_at = null, "
                    + "health = 'UNKNOWN' where code = ?", code);
        }
        fake.reset();
        fakeRetry.reset();
        fakeBreaker.reset();
    }

    @AfterEach
    protected void releaseSources() {
        jdbc.update("update sources set enabled = true where code in ('FAKE', 'FAKE_RETRY', 'FAKE_BREAKER')");
    }

    protected UUID sourceId(String code) {
        return jdbc.queryForObject("select id from sources where code = ?", UUID.class, code);
    }

    protected UUID addTarget(String code, String identifier) {
        return addTarget(code, identifier, true);
    }

    protected UUID addTarget(String code, String identifier, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into source_targets (id, source_id, identifier, enabled, created_at, updated_at) "
                + "values (?, ?, ?, ?, now(), now())", id, sourceId(code), identifier, enabled);
        return id;
    }

    protected int rawPostingCount(String code) {
        return jdbc.queryForObject("select count(*) from raw_job_postings where source_id = ?", Integer.class,
                sourceId(code));
    }

    protected int runCount(String code) {
        return jdbc.queryForObject("select count(*) from ingestion_runs where source_id = ?", Integer.class,
                sourceId(code));
    }

    protected String health(String code) {
        return jdbc.queryForObject("select health from sources where code = ?", String.class, code);
    }
}
