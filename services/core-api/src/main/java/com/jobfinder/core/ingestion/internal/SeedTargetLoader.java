package com.jobfinder.core.ingestion.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.InvalidSourceTargetException;
import com.jobfinder.core.ingestion.SourceTargetService;
import com.jobfinder.core.ingestion.SourceTargetView;
import com.jobfinder.core.ingestion.UnknownSourceException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the seed list of ATS board tokens ({@code ingestion/seed-targets.json}) once the application is up.
 * The file is {@code {sources, targets}}: {@code targets} are {@code {source, token, company}} entries, and
 * {@code sources} holds starting tuning per source (a gentler {@code requestsPerSecond} for a board host that
 * rate-limits hard), applied only to keys the source has not set itself. Loading is idempotent: an entry already present, enabled or
 * not, is left alone, so restarts add only what is new and an admin's changes survive. One bad entry is
 * logged and skipped; it never stops the others or the startup. The file is data, not code: it holds public
 * board tokens and company names, nothing personal.
 */
@Component
class SeedTargetLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedTargetLoader.class);

    record Entry(String source, String token, String company) {
    }

    record Result(int added, int existing, int skipped) {
    }

    record SeedFile(Map<String, Map<String, Number>> sources, List<Entry> targets) {
    }

    private final IngestionProperties properties;
    private static final Set<String> TUNING_KEYS = Set.of("intervalMinutes", "jitterSeconds", "requestsPerSecond",
            "retryMaxAttempts");

    private final SourceTargetService targets;
    private final SourceStore sources;
    private final IngestionRunner runner;
    private final JsonMapper json;

    SeedTargetLoader(IngestionProperties properties, SourceTargetService targets, SourceStore sources,
            IngestionRunner runner, JsonMapper json) {
        this.properties = properties;
        this.targets = targets;
        this.sources = sources;
        this.runner = runner;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    void loadOnStartup() {
        if (!properties.seed().enabled()) {
            return;
        }
        try {
            Result result = load(properties.seed().location());
            log.info("Seed targets loaded: {} added, {} already present, {} skipped", result.added(),
                    result.existing(), result.skipped());
        } catch (RuntimeException e) {
            // A broken seed file must not take the API down.
            log.error("Seed targets could not be loaded: {}", e.toString());
        }
    }

    Result load(String location) {
        SeedFile file = read(location);
        applyTuning(file.sources());
        List<Entry> entries = file.targets() == null ? List.of() : file.targets();
        int added = 0;
        int existing = 0;
        int skipped = 0;
        for (Entry entry : entries) {
            try {
                SourceTargetView view = targets.addTarget(entry.source(), entry.token(), entry.company());
                if (view.created()) {
                    added++;
                } else {
                    existing++;
                }
            } catch (UnknownSourceException | InvalidSourceTargetException e) {
                skipped++;
                log.warn("Seed entry skipped ({} {}): {}", entry.source(), entry.token(), e.getMessage());
            }
        }
        return new Result(added, existing, skipped);
    }

    /** Starting tuning for a source: only keys it has not set (an admin's value always wins). */
    private void applyTuning(Map<String, Map<String, Number>> tuning) {
        if (tuning == null) {
            return;
        }
        tuning.forEach((code, values) -> {
            if (!runner.adapters().containsKey(code) || values == null) {
                return;
            }
            Map<String, Number> known = new java.util.LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (TUNING_KEYS.contains(key) && value != null) {
                    known.put(key, value);
                }
            });
            if (!known.isEmpty()) {
                sources.register(code, runner.adapters().get(code).kind());
                sources.applyDefaultConfig(code, json.writeValueAsString(known));
            }
        });
    }

    private SeedFile read(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return json.readValue(in, SeedFile.class);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Cannot read the seed targets at " + location, e);
        }
    }
}
