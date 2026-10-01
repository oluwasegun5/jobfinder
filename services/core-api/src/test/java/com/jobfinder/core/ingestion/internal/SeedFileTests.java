package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** The shipped seed list is data, but a typo in it would silently waste a target, so its shape is tested. */
class SeedFileTests {

    private static final Set<String> SOURCES = Set.of("GREENHOUSE", "LEVER", "ASHBY", "WORKABLE", "SMARTRECRUITERS",
            "RECRUITEE");
    /** Aggregator searches: a country and a query for the two keyed sources, {@code all} for the three open feeds. */
    private static final Map<String, Pattern> AGGREGATORS = Map.of(
            "ADZUNA", Pattern.compile("[a-z]{2}:[^:\\p{Cntrl}]{1,100}"),
            "JSEARCH", Pattern.compile("[a-z]{2}:[^:\\p{Cntrl}]{1,100}"),
            "REMOTIVE", Pattern.compile("all"),
            "ARBEITNOW", Pattern.compile("all"),
            "REMOTEOK", Pattern.compile("all"));
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private SeedTargetLoader.SeedFile file() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        try (InputStream in = getClass().getResourceAsStream("/ingestion/seed-targets.json")) {
            assertThat(in).as("seed file on the classpath").isNotNull();
            return json.readValue(in, SeedTargetLoader.SeedFile.class);
        }
    }

    private List<SeedTargetLoader.Entry> entries() throws Exception {
        return file().targets();
    }

    @Test
    void sourceTuningNamesKnownSourcesAndKeys() throws Exception {
        file().sources().forEach((code, values) -> {
            assertThat(code).satisfiesAnyOf(c -> assertThat(c).isIn(SOURCES),
                    c -> assertThat(c).isIn(AGGREGATORS.keySet()));
            assertThat(values.keySet()).isSubsetOf("intervalMinutes", "jitterSeconds", "requestsPerSecond",
                    "retryMaxAttempts");
        });
    }

    @Test
    void everyEntryNamesAKnownSourceAValidTokenAndACompany() throws Exception {
        for (SeedTargetLoader.Entry entry : entries()) {
            if (AGGREGATORS.containsKey(entry.source())) {
                assertThat(entry.token()).as(entry.toString()).matches(AGGREGATORS.get(entry.source()));
                assertThat(entry.company()).as("an aggregator search has no company: " + entry).isNull();
                continue;
            }
            assertThat(entry.source()).as(entry.toString()).isIn(SOURCES);
            assertThat(entry.token()).as(entry.toString()).matches(TOKEN);
            assertThat(entry.company()).as(entry.toString()).isNotBlank().hasSizeLessThanOrEqualTo(300);
            assertThat(Names.company(entry.company())).as(entry.toString()).isNotEmpty();
        }
    }

    @Test
    void thereAreNoDuplicatesAndEverySourceIsRepresented() throws Exception {
        Set<String> seen = new HashSet<>();
        Set<String> sources = new HashSet<>();
        for (SeedTargetLoader.Entry entry : entries()) {
            assertThat(seen.add(entry.source() + "/" + entry.token().toLowerCase())).as(entry.toString()).isTrue();
            sources.add(entry.source());
        }
        Set<String> expected = new HashSet<>(SOURCES);
        expected.addAll(AGGREGATORS.keySet());
        assertThat(sources).isEqualTo(expected);
        assertThat(seen.size()).isGreaterThanOrEqualTo(200);
    }
}
