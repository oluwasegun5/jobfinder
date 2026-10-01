package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.jobfinder.core.ingestion.IngestionTestSupport;
import com.jobfinder.core.ingestion.SourceTargetService;
import com.jobfinder.core.ingestion.SourceUnavailableException;

/**
 * A keyed aggregator without its key stays registered and enabled but is skipped, with a clear reason, and
 * nothing about it stops the application or the other sources. The keys are forced blank here, whatever the
 * developer's shell has.
 */
@TestPropertySource(properties = {
        "app.ingestion.aggregators.adzuna.app-id=",
        "app.ingestion.aggregators.adzuna.app-key=",
        "app.ingestion.aggregators.jsearch.api-key=" })
class AggregatorUnavailableTests extends IngestionTestSupport {

    @Autowired
    SourceTargetService targets;

    @Autowired
    IngestionScheduler scheduler;

    @BeforeEach
    void clean() {
        for (String code : new String[] { "ADZUNA", "JSEARCH" }) {
            jdbc.update("delete from ingestion_runs where source_id = (select id from sources where code = ?)", code);
            jdbc.update("delete from source_targets where source_id = (select id from sources where code = ?)", code);
            jdbc.update("update sources set enabled = true, last_run_at = null where code = ?", code);
        }
    }

    @AfterEach
    void restore() {
        jdbc.update("update sources set enabled = true");
    }

    @Test
    void theSourcesAreRegisteredEnabledAndCarryTheirAttribution() {
        for (String code : new String[] { "ADZUNA", "JSEARCH" }) {
            assertThat(jdbc.queryForObject("select count(*) from sources where code = ? and enabled", Integer.class,
                    code)).as(code).isEqualTo(1);
            assertThat(jdbc.queryForObject("select attribution_text from sources where code = ?", String.class, code))
                    .as(code).isNotBlank();
        }
        assertThat(jdbc.queryForObject("select attribution_text from sources where code = 'ADZUNA'", String.class))
                .isEqualTo("Jobs by Adzuna");
    }

    @Test
    void aManualRunIsRefusedWithTheReasonAndRecordsNothing() {
        targets.addTarget("ADZUNA", "gb:software engineer", null);

        assertThat(ingestion.unavailableReason("ADZUNA")).hasValueSatisfying(
                reason -> assertThat(reason).contains("ADZUNA_APP_ID", "ADZUNA_APP_KEY"));
        assertThat(ingestion.unavailableReason("JSEARCH")).hasValueSatisfying(
                reason -> assertThat(reason).contains("JSEARCH_RAPIDAPI_KEY"));
        assertThat(ingestion.unavailableReason("REMOTEOK")).isEmpty();
        assertThatThrownBy(() -> ingestion.runNow("ADZUNA")).isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("ADZUNA_APP_ID");
        assertThat(runCount("ADZUNA")).isZero();
    }

    @Test
    void theSchedulerSkipsThemWithoutRecordingARun() {
        targets.addTarget("ADZUNA", "gb:software engineer", null);
        targets.addTarget("JSEARCH", "us:software engineer", null);
        jdbc.update("update sources set enabled = false where code not in ('ADZUNA', 'JSEARCH')");

        assertThat(scheduler.tick(Instant.now())).isEmpty();

        assertThat(runCount("ADZUNA")).isZero();
        assertThat(runCount("JSEARCH")).isZero();
        assertThat(health("ADZUNA")).isEqualTo("UNKNOWN");
    }
}
