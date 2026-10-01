package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.jobfinder.core.ingestion.IngestionTestSupport;

/** With seeding on (as in a real run), the application comes up with the shipped targets already in place. */
@TestPropertySource(properties = "app.ingestion.seed.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeedOnStartupTests extends IngestionTestSupport {

    @Autowired
    SeedTargetLoader loader;

    @Autowired
    JdbcTemplate template;

    @Test
    void theShippedSeedListIsLoadedOnStartupAndLoadingAgainIsIdempotent() {
        int seeded = template.queryForObject("""
                select count(*) from source_targets t join sources s on s.id = t.source_id
                where s.code in ('GREENHOUSE','LEVER','ASHBY','WORKABLE','SMARTRECRUITERS','RECRUITEE')""",
                Integer.class);
        assertThat(seeded).isGreaterThanOrEqualTo(200);

        SeedTargetLoader.Result again = loader.load("classpath:ingestion/seed-targets.json");

        assertThat(again.added()).isZero();
        assertThat(again.existing()).isEqualTo(seeded);
        assertThat(again.skipped()).isZero();
    }
}
