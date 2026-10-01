package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.ingestion.IngestionTestSupport;

/** Loading a seed list: adds what is missing, skips what is unusable, and never undoes an admin's change. */
class SeedTargetLoaderTests extends IngestionTestSupport {

    private static final String SAMPLE = "classpath:ats/seed-sample.json";

    @Autowired
    SeedTargetLoader loader;

    @AfterEach
    void removeSeedTargets() {
        jdbc.update("update sources set config = '{}'::jsonb where code = 'LEVER'");
        jdbc.update("delete from source_targets where identifier like 'seed-test-%'");
        jdbc.update("delete from companies where name like 'Seed Test%'");
    }

    @Test
    void addsTheUsableEntriesAndSkipsTheRest() {
        SeedTargetLoader.Result result = loader.load(SAMPLE);

        assertThat(result).isEqualTo(new SeedTargetLoader.Result(2, 1, 2));
        assertThat(count("GREENHOUSE", "seed-test-a")).isEqualTo(1);
        assertThat(count("LEVER", "seed-test-b")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select c.name from source_targets t join companies c on c.id = t.company_id
                where t.identifier = 'seed-test-a'""", String.class)).isEqualTo("Seed Test Alpha");
    }

    @Test
    void loadingTwiceAddsNothingAndKeepsAnAdminsChoice() {
        loader.load(SAMPLE);
        jdbc.update("update source_targets set enabled = false where identifier = 'seed-test-a'");

        SeedTargetLoader.Result second = loader.load(SAMPLE);

        assertThat(second).isEqualTo(new SeedTargetLoader.Result(0, 3, 2));
        assertThat(jdbc.queryForObject("select enabled from source_targets where identifier = 'seed-test-a'",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from source_targets where identifier like 'seed-test-%'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void appliesStartingTuningOnlyToKeysTheSourceHasNotSet() {
        loader.load(SAMPLE);
        assertThat(jdbc.queryForObject("select config ->> 'requestsPerSecond' from sources where code = 'LEVER'",
                String.class)).isEqualTo("0.25");
        assertThat(jdbc.queryForObject("select jsonb_exists(config, 'bogusKey') from sources where code = 'LEVER'", Boolean.class))
                .isFalse();

        jdbc.update("update sources set config = '{\"requestsPerSecond\": 3}'::jsonb where code = 'LEVER'");
        loader.load(SAMPLE);

        assertThat(jdbc.queryForObject("select config ->> 'requestsPerSecond' from sources where code = 'LEVER'",
                String.class)).isEqualTo("3");
    }

    @Test
    void anUnreadableSeedFileIsAnError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.load("classpath:ats/does-not-exist.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    private int count(String source, String token) {
        return jdbc.queryForObject("""
                select count(*) from source_targets t join sources s on s.id = t.source_id
                where s.code = ? and t.identifier = ?""", Integer.class, source, token);
    }
}
