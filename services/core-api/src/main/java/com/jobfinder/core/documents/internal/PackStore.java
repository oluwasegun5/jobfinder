package com.jobfinder.core.documents.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.documents.internal.WritingDtos.PackOptions;
import com.jobfinder.core.documents.internal.WritingDtos.PackStatus;
import com.jobfinder.core.documents.internal.WritingDtos.PartError;
import com.jobfinder.core.documents.internal.WritingDtos.PartState;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SQL of {@code application_packs}. Every read and write takes the owner's user id: a pack is only ever found
 * through its owner.
 */
@Repository
class PackStore {

    /** What is stored for one part of a pack: its state, its document when READY, and why not otherwise. */
    record PartRecord(PartState state, UUID documentId, PartError error) {

        static PartRecord pending() {
            return new PartRecord(PartState.PENDING, null, null);
        }

        static PartRecord ready(UUID documentId) {
            return new PartRecord(PartState.READY, documentId, null);
        }
    }

    /** One pack row; {@code parts} is keyed by document type name, in the order the parts were asked for. */
    record PackRow(UUID id, UUID userId, UUID jobId, String jobTitle, String jobCompany, UUID baseResumeVersionId,
            PackStatus status, PackOptions options, Map<String, PartRecord> parts, int version, Instant createdAt,
            Instant updatedAt) {
    }

    private static final TypeReference<LinkedHashMap<String, PartRecord>> PARTS = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, user_id, job_id, job_title, job_company, base_resume_version_id, status, options::text as options,
            parts::text as parts, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    PackStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Inserts a GENERATING pack. Throws DuplicateKeyException if the user already has one for this job and resume. */
    void insert(UUID id, UUID userId, UUID jobId, String jobTitle, String jobCompany, UUID versionId,
            PackOptions options, Map<String, PartRecord> parts) {
        jdbc.sql("""
                insert into application_packs (id, user_id, job_id, job_title, job_company, base_resume_version_id,
                        status, options, parts, version, created_at, updated_at)
                values (:id, :userId, :jobId, :jobTitle, :jobCompany, :versionId, 'GENERATING',
                        cast(:options as jsonb), cast(:parts as jsonb), 1, now(), now())
                """).param("id", id).param("userId", userId).param("jobId", jobId).param("jobTitle", jobTitle)
                .param("jobCompany", jobCompany).param("versionId", versionId).param("options", write(options))
                .param("parts", write(parts)).update();
    }

    /**
     * Takes the pack for a new run: GENERATING with these options and parts. False if a run is in flight (the pack is
     * GENERATING and not older than the cutoff): the caller must not start another.
     */
    boolean beginRun(UUID id, UUID userId, PackOptions options, Map<String, PartRecord> parts, Instant cutoff) {
        return jdbc.sql("""
                update application_packs
                   set status = 'GENERATING', options = cast(:options as jsonb), parts = cast(:parts as jsonb),
                       version = version + 1, updated_at = now()
                 where id = :id and user_id = :userId and (status <> 'GENERATING' or updated_at < :cutoff)
                """).param("id", id).param("userId", userId).param("options", write(options))
                .param("parts", write(parts)).param("cutoff", Timestamp.from(cutoff)).update() == 1;
    }

    void saveParts(UUID id, PackStatus status, Map<String, PartRecord> parts) {
        jdbc.sql("""
                update application_packs
                   set status = :status, parts = cast(:parts as jsonb), version = version + 1, updated_at = now()
                 where id = :id
                """).param("id", id).param("status", status.name()).param("parts", write(parts)).update();
    }

    Optional<PackRow> find(UUID userId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from application_packs where id = :id and user_id = :userId")
                .param("id", id).param("userId", userId).query((rs, row) -> row(rs)).optional();
    }

    Optional<PackRow> findByJob(UUID userId, UUID jobId, UUID versionId) {
        return jdbc.sql("select " + COLUMNS + """
                 from application_packs
                 where user_id = :userId and job_id = :jobId and base_resume_version_id = :versionId
                """).param("userId", userId).param("jobId", jobId).param("versionId", versionId)
                .query((rs, row) -> row(rs)).optional();
    }

    List<PackRow> list(UUID userId, UUID jobId, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                 from application_packs
                 where user_id = :userId and (cast(:jobId as uuid) is null or job_id = :jobId)
                 order by created_at desc, id
                 limit :limit
                """).param("userId", userId).param("jobId", jobId).param("limit", limit)
                .query((rs, row) -> row(rs)).list();
    }

    /** Account deletion: the pack rows go with the user (the documents they point at are purged separately). */
    void purgeAll(UUID userId) {
        jdbc.sql("delete from application_packs where user_id = :userId").param("userId", userId).update();
    }

    private PackRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new PackRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                    rs.getObject("job_id", UUID.class), rs.getString("job_title"), rs.getString("job_company"),
                    rs.getObject("base_resume_version_id", UUID.class), PackStatus.valueOf(rs.getString("status")),
                    json.readValue(rs.getString("options"), PackOptions.class),
                    json.readValue(rs.getString("parts"), PARTS), rs.getInt("version"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored pack JSON is unreadable", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise pack JSON", e);
        }
    }
}
