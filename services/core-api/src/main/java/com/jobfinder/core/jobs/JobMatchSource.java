package com.jobfinder.core.jobs;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of the job tables for matching (docs/adr/0026-matching-engine.md): the stage-1 filters and the
 * cosine recall of stage 2 in one query, and the job content a score is made from. Jobs stay owned by ingestion;
 * this only reads.
 */
public interface JobMatchSource {

    /**
     * The jobs nearest to {@code vector} by cosine distance among those that pass {@code selection}, nearest first,
     * at most {@code limit}. Only active, unexpired jobs that have an embedding of {@code model} and are not hidden
     * by {@code selection.userId()} are considered.
     */
    List<RecalledJob> recall(String model, float[] vector, JobSelection selection, int limit);

    /** The jobs with these ids, whatever their status, in no particular order; unknown ids are left out. */
    List<JobForMatching> jobs(Collection<UUID> ids);

    /** Cosine similarity between {@code vector} and the job's embedding, if the job has one of {@code model}. */
    Optional<Double> similarity(UUID jobId, String model, float[] vector);
}
