package com.jobfinder.core.ingestion.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.jobfinder.core.ingestion.IngestionRunSummary;
import com.jobfinder.core.ingestion.IngestionService;
import com.jobfinder.core.ingestion.JobSourceAdapter;
import com.jobfinder.core.ingestion.SourceUnavailableException;
import com.jobfinder.core.ingestion.UnknownSourceException;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * Runs a source under a ShedLock lock named after it, so only one run of a given source happens at
 * a time across all instances, whether it was started by the scheduler or by hand. Different
 * sources run independently.
 */
@Service
class IngestionRunner implements IngestionService {

    private final Map<String, JobSourceAdapter> adapters = new TreeMap<>();
    private final SourceStore sources;
    private final IngestionPipeline pipeline;
    private final LockingTaskExecutor lockExecutor;
    private final IngestionProperties properties;

    IngestionRunner(List<JobSourceAdapter> adapterBeans, SourceStore sources, IngestionPipeline pipeline,
            LockingTaskExecutor lockExecutor, IngestionProperties properties) {
        for (JobSourceAdapter adapter : adapterBeans) {
            SourceRegistrar.validateCode(adapter.sourceCode());
            if (adapters.put(adapter.sourceCode(), adapter) != null) {
                throw new IllegalStateException("Two job source adapters declare the code " + adapter.sourceCode());
            }
        }
        this.sources = sources;
        this.pipeline = pipeline;
        this.lockExecutor = lockExecutor;
        this.properties = properties;
    }

    Map<String, JobSourceAdapter> adapters() {
        return adapters;
    }

    @Override
    public Optional<IngestionRunSummary> runNow(String sourceCode) {
        JobSourceAdapter adapter = adapters.get(sourceCode);
        SourceStore.SourceRow source = adapter == null ? null : sources.findByCode(sourceCode).orElse(null);
        if (source == null) {
            throw new UnknownSourceException(sourceCode);
        }
        adapter.unavailableReason().ifPresent(reason -> {
            throw new SourceUnavailableException(sourceCode, reason);
        });
        LockConfiguration lock = new LockConfiguration(Instant.now(), lockName(sourceCode),
                properties.lockAtMostFor(), Duration.ZERO);
        try {
            var result = lockExecutor.executeWithLock(() -> pipeline.execute(source, adapter), lock);
            return result.wasExecuted() ? Optional.ofNullable(result.getResult()) : Optional.empty();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Ingestion run of " + sourceCode + " failed", e);
        }
    }

    @Override
    public Optional<String> unavailableReason(String sourceCode) {
        JobSourceAdapter adapter = adapters.get(sourceCode);
        if (adapter == null) {
            throw new UnknownSourceException(sourceCode);
        }
        return adapter.unavailableReason();
    }

    static String lockName(String sourceCode) {
        return "ingestion:" + sourceCode;
    }
}
