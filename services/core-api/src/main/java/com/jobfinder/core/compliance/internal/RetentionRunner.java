package com.jobfinder.core.compliance.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionTask;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Runs every module's {@link RetentionTask}. One task failing is logged and counted and never stops the others: a
 * retention bug in one module must not leave every other store unpruned.
 */
@Component
class RetentionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);

    private final List<RetentionTask> tasks;
    private final MeterRegistry meters;

    RetentionRunner(List<RetentionTask> tasks, MeterRegistry meters) {
        this.tasks = tasks.stream().sorted(java.util.Comparator.comparing(RetentionTask::name)).toList();
        this.meters = meters;
    }

    /** Runs all tasks as of {@code now}; returns the number of records each removed (-1 for a task that failed). */
    Map<String, Integer> runAll(Instant now) {
        Map<String, Integer> removed = new LinkedHashMap<>();
        for (RetentionTask task : tasks) {
            try {
                int count = task.purge(now);
                removed.put(task.name(), count);
                meters.counter("retention_purged_total", "task", task.name()).increment(count);
                if (count > 0) {
                    log.info("Retention task {} removed {} records", task.name(), count);
                }
            } catch (RuntimeException e) {
                removed.put(task.name(), -1);
                meters.counter("retention_failures_total", "task", task.name()).increment();
                log.error("Retention task {} failed", task.name(), e);
            }
        }
        return removed;
    }

    List<String> taskNames() {
        return tasks.stream().map(RetentionTask::name).toList();
    }
}
