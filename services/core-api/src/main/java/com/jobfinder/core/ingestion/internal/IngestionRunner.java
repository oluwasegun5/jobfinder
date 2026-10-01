package com.jobfinder.core.ingestion.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.jobfinder.core.ingestion.IngestionRunSummary;
import com.jobfinder.core.ingestion.IngestionService;
import com.jobfinder.core.ingestion.JobSourceAdapter;
import com.jobfinder.core.ingestion.SourceAdminService;
import com.jobfinder.core.ingestion.SourceUnavailableException;
import com.jobfinder.core.ingestion.UnknownSourceException;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;

import jakarta.annotation.PreDestroy;

/**
 * Runs a source under a ShedLock lock named after it, so only one run of a given source happens at
 * a time across all instances, whether it was started by the scheduler or by hand. Different
 * sources run independently.
 */
@Service
class IngestionRunner implements IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final Map<String, JobSourceAdapter> adapters = new TreeMap<>();
    private final SourceStore sources;
    private final IngestionPipeline pipeline;
    private final LockingTaskExecutor lockExecutor;
    private final LockProvider lockProvider;
    private final IngestionProperties properties;
    private final ExecutorService manualRuns = Executors.newVirtualThreadPerTaskExecutor();

    IngestionRunner(List<JobSourceAdapter> adapterBeans, SourceStore sources, IngestionPipeline pipeline,
            LockingTaskExecutor lockExecutor, LockProvider lockProvider, IngestionProperties properties) {
        for (JobSourceAdapter adapter : adapterBeans) {
            SourceRegistrar.validateCode(adapter.sourceCode());
            if (adapters.put(adapter.sourceCode(), adapter) != null) {
                throw new IllegalStateException("Two job source adapters declare the code " + adapter.sourceCode());
            }
        }
        this.sources = sources;
        this.pipeline = pipeline;
        this.lockExecutor = lockExecutor;
        this.lockProvider = lockProvider;
        this.properties = properties;
    }

    Map<String, JobSourceAdapter> adapters() {
        return adapters;
    }

    private record Resolved(JobSourceAdapter adapter, SourceStore.SourceRow source) {
    }

    /** The adapter and source row of a source that can run now. */
    private Resolved resolveRunnable(String sourceCode) {
        JobSourceAdapter adapter = adapters.get(sourceCode);
        SourceStore.SourceRow source = adapter == null ? null : sources.findByCode(sourceCode).orElse(null);
        if (source == null) {
            throw new UnknownSourceException(sourceCode);
        }
        adapter.unavailableReason().ifPresent(reason -> {
            throw new SourceUnavailableException(sourceCode, reason);
        });
        return new Resolved(adapter, source);
    }

    private LockConfiguration lockFor(String sourceCode) {
        return new LockConfiguration(Instant.now(), lockName(sourceCode), properties.lockAtMostFor(), Duration.ZERO);
    }

    @Override
    public Optional<IngestionRunSummary> runNow(String sourceCode) {
        Resolved resolved = resolveRunnable(sourceCode);
        JobSourceAdapter adapter = resolved.adapter();
        SourceStore.SourceRow source = resolved.source();
        LockConfiguration lock = lockFor(sourceCode);
        try {
            var result = lockExecutor.executeWithLock(() -> pipeline.execute(source, adapter), lock);
            return result.wasExecuted() ? Optional.ofNullable(result.getResult()) : Optional.empty();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Ingestion run of " + sourceCode + " failed", e);
        }
    }

    /**
     * Starts a run on its own thread and returns at once, once it holds the source's lock (the same one
     * {@link #runNow} and the scheduler take, so runs of a source never overlap).
     */
    SourceAdminService.RunStart startAsync(String sourceCode) {
        Resolved resolved = resolveRunnable(sourceCode);
        Optional<SimpleLock> held = lockProvider.lock(lockFor(sourceCode));
        if (held.isEmpty()) {
            return SourceAdminService.RunStart.ALREADY_RUNNING;
        }
        SimpleLock lock = held.get();
        try {
            manualRuns.execute(() -> {
                try {
                    pipeline.execute(resolved.source(), resolved.adapter());
                } catch (RuntimeException | Error e) {
                    log.error("Manual run of source {} failed", sourceCode, e);
                } finally {
                    lock.unlock();
                }
            });
        } catch (RuntimeException e) {
            lock.unlock();
            throw e;
        }
        return SourceAdminService.RunStart.STARTED;
    }

    @PreDestroy
    void shutdown() {
        manualRuns.close();
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
