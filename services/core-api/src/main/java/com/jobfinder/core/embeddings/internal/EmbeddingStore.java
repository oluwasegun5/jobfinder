package com.jobfinder.core.embeddings.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the text sources and writes the vectors. The columns live on {@code jobs} (ingestion's table) and
 * {@code resume_versions} (profile's); this class is the only code that reads or writes the embedding columns, and
 * it reads only the few columns the embedded text is built from.
 *
 * <p>Methods taking {@code lock} select {@code FOR UPDATE} (in id order, so two batches cannot deadlock): a content
 * change commits either before the lock is taken (and is seen) or after the vector is written (and is then queued as
 * stale by its own after-commit hook), never in between.
 */
@Component
class EmbeddingStore {

    record JobRow(UUID id, String title, String company, String descriptionText, String status, String model,
            String inputHash) {
    }

    record ResumeVersionRow(UUID id, UUID userId, String structuredJson, String model, String inputHash) {
    }

    private final JdbcClient jdbc;

    EmbeddingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<JobRow> jobs(Collection<UUID> ids, boolean lock) {
        return jdbc.sql("""
                select j.id, j.title, c.name as company, j.description_text, j.status, j.embedding_model,
                       j.embedding_input_hash
                  from jobs j join companies c on c.id = j.company_id
                 where j.id in (:ids)
                 order by j.id
                """ + (lock ? " for update of j" : ""))
                .param("ids", ids)
                .query((rs, row) -> new JobRow(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("company"), rs.getString("description_text"), rs.getString("status"),
                        rs.getString("embedding_model"), rs.getString("embedding_input_hash")))
                .list();
    }

    List<ResumeVersionRow> resumeVersions(Collection<UUID> ids, boolean lock) {
        return jdbc.sql("""
                select v.id, r.user_id, v.structured::text as structured, v.embedding_model, v.embedding_input_hash
                  from resume_versions v join resumes r on r.id = v.resume_id
                 where v.id in (:ids)
                 order by v.id
                """ + (lock ? " for update of v" : ""))
                .param("ids", ids)
                .query((rs, row) -> new ResumeVersionRow(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("structured"),
                        rs.getString("embedding_model"), rs.getString("embedding_input_hash")))
                .list();
    }

    Optional<JobRow> job(UUID id) {
        return jobs(List.of(id), false).stream().findFirst();
    }

    Optional<ResumeVersionRow> resumeVersion(UUID id) {
        return resumeVersions(List.of(id), false).stream().findFirst();
    }

    /** {@code vector} is a pgvector literal such as {@code [0.1,0.2]}. */
    void writeJob(UUID id, String model, String inputHash, String vector) {
        jdbc.sql("""
                update jobs set embedding = cast(:vector as vector), embedding_model = :model,
                       embedding_input_hash = :hash, embedded_at = now()
                 where id = :id
                """).param("vector", vector).param("model", model).param("hash", inputHash).param("id", id).update();
    }

    void writeResumeVersion(UUID id, String model, String inputHash, String vector) {
        jdbc.sql("""
                update resume_versions set embedding = cast(:vector as vector), embedding_model = :model,
                       embedding_input_hash = :hash, embedded_at = now()
                 where id = :id
                """).param("vector", vector).param("model", model).param("hash", inputHash).param("id", id).update();
    }

    /**
     * The next page of active jobs that may need an embedding: none yet, another model, or touched since it was
     * made. (Whether the text really changed is decided by hashing it; this only skips rows that cannot be stale.)
     */
    List<JobRow> jobCandidates(UUID after, String model, int limit) {
        return jdbc.sql("""
                select j.id, j.title, c.name as company, j.description_text, j.status, j.embedding_model,
                       j.embedding_input_hash
                  from jobs j join companies c on c.id = j.company_id
                 where j.status = 'ACTIVE' and j.id > :after
                   and (j.embedding is null or j.embedding_model <> :model or j.embedded_at < j.updated_at)
                 order by j.id
                 limit :limit
                """)
                .param("after", after).param("model", model).param("limit", limit)
                .query((rs, row) -> new JobRow(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("company"), rs.getString("description_text"), rs.getString("status"),
                        rs.getString("embedding_model"), rs.getString("embedding_input_hash")))
                .list();
    }

    /** As {@link #jobCandidates}, for resume versions that have content. */
    List<ResumeVersionRow> resumeVersionCandidates(UUID after, String model, int limit) {
        return jdbc.sql("""
                select v.id, r.user_id, v.structured::text as structured, v.embedding_model, v.embedding_input_hash
                  from resume_versions v join resumes r on r.id = v.resume_id
                 where v.structured is not null and v.id > :after
                   and (v.embedding is null or v.embedding_model <> :model or v.embedded_at < v.updated_at)
                 order by v.id
                 limit :limit
                """)
                .param("after", after).param("model", model).param("limit", limit)
                .query((rs, row) -> new ResumeVersionRow(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("structured"),
                        rs.getString("embedding_model"), rs.getString("embedding_input_hash")))
                .list();
    }
}
