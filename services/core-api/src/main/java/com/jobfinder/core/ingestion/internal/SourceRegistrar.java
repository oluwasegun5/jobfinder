package com.jobfinder.core.ingestion.internal;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.JobSourceAdapter;

/**
 * Makes sure every adapter has a {@code sources} row, once the application is up. Existing rows are
 * left as they are, so tuning an admin made survives restarts. New rows start enabled; with no
 * targets yet, enabled does nothing. A source's required attribution is rewritten every time (its adapter owns
 * the wording), and a source that cannot run (no API key) is reported once, here.
 */
@Component
class SourceRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistrar.class);

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final IngestionRunner runner;
    private final SourceStore sources;

    SourceRegistrar(IngestionRunner runner, SourceStore sources) {
        this.runner = runner;
        this.sources = sources;
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerAll() {
        runner.adapters().forEach((code, adapter) -> {
            sources.register(code, adapter.kind());
            adapter.attribution().ifPresent(attribution -> sources.updateAttribution(code, attribution));
            adapter.unavailableReason().ifPresent(
                    reason -> log.warn("Source {} is registered but will be skipped: {}", code, reason));
        });
    }

    /** Codes are upper snake case, at most 40 characters, which also keeps lock names within 64. */
    static void validateCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalStateException(
                    "Job source code must be UPPER_SNAKE_CASE, at most 40 characters: " + code);
        }
    }
}
