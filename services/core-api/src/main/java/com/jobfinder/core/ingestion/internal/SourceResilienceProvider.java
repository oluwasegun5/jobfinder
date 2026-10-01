package com.jobfinder.core.ingestion.internal;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** One {@link SourceResilience} per source code, created on first use. */
@Component
class SourceResilienceProvider {

    private final ConcurrentHashMap<String, SourceResilience> bySource = new ConcurrentHashMap<>();

    SourceResilience forSource(SourceStore.SourceRow source, IngestionProperties.Defaults defaults) {
        return bySource.computeIfAbsent(source.code(),
                code -> new SourceResilience(code, source.settings(), defaults));
    }
}
