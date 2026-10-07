package com.jobfinder.core.compliance;

import java.time.Instant;

/**
 * One module's retention rule: delete what is older than the module's retention period. Implemented as a Spring bean in
 * the module that owns the data; {@code compliance.internal.RetentionRunner} calls every one daily. A task must be
 * idempotent (running twice deletes nothing more) and must only delete data that is truly past its period, because
 * there is no undo.
 */
public interface RetentionTask {

    /** A stable lower-case name, used in logs and as the {@code task} tag of the metric. */
    String name();

    /** Deletes what is past retention as of {@code now} and returns how many records (rows, objects) it removed. */
    int purge(Instant now);
}
