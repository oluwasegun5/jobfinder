package com.jobfinder.core.documents.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.documents.internal.DocumentDtos.Change;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentStatus;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentType;
import com.jobfinder.core.documents.internal.DocumentDtos.FactCheck;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SQL of {@code generated_documents}. Every read and write takes the owner's user id: a document is only ever
 * found through its owner.
 */
@Repository
class DocumentStore {

    /** One row, with its JSON columns read. {@code content} and {@code factCheck} are null while GENERATING. */
    record Row(UUID id, UUID userId, DocumentType type, DocumentStatus status, UUID jobId, String jobTitle,
            String jobCompany, UUID baseResumeVersionId, String promptVersion, String model, JsonNode source,
            JsonNode content, List<Change> changes, FactCheck factCheck, int version, Instant createdAt,
            Instant updatedAt, Instant approvedAt) {
    }

    private static final TypeReference<List<Change>> CHANGES = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, user_id, type, status, job_id, job_title, job_company, base_resume_version_id, prompt_version, model,
            source_content::text as source_content, content::text as content, changes::text as changes,
            fact_check::text as fact_check, version, created_at, updated_at, approved_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    DocumentStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Inserts the GENERATING placeholder. Throws DuplicateKeyException if an open draft exists for the key. */
    void insertPlaceholder(UUID id, UUID userId, UUID jobId, String jobTitle, String jobCompany, UUID versionId,
            String promptVersion, JsonNode source) {
        jdbc.sql("""
                insert into generated_documents (id, user_id, type, status, job_id, job_title, job_company,
                        base_resume_version_id, prompt_version, source_content, changes, version, created_at,
                        updated_at)
                values (:id, :userId, 'TAILORED_RESUME', 'GENERATING', :jobId, :jobTitle, :jobCompany, :versionId,
                        :promptVersion, cast(:source as jsonb), '[]'::jsonb, 1, now(), now())
                """).param("id", id).param("userId", userId).param("jobId", jobId).param("jobTitle", jobTitle)
                .param("jobCompany", jobCompany).param("versionId", versionId)
                .param("promptVersion", promptVersion).param("source", write(source)).update();
    }

    /** Fills the placeholder. False if it is gone (the user deleted the draft while it was being made). */
    boolean complete(UUID id, DocumentStatus status, String model, String promptVersion, JsonNode content,
            List<Change> changes, FactCheck factCheck) {
        return jdbc.sql("""
                update generated_documents
                   set status = :status, model = :model, prompt_version = :promptVersion,
                       content = cast(:content as jsonb), changes = cast(:changes as jsonb),
                       fact_check = cast(:factCheck as jsonb), updated_at = now()
                 where id = :id and status = 'GENERATING'
                """).param("id", id).param("status", status.name()).param("model", model)
                .param("promptVersion", promptVersion).param("content", write(content))
                .param("changes", write(changes)).param("factCheck", write(factCheck)).update() == 1;
    }

    /** Stores the result of an edit or a re-check. Never touches an APPROVED row (the trigger would refuse). */
    void save(UUID id, DocumentStatus status, JsonNode content, List<Change> changes, FactCheck factCheck) {
        jdbc.sql("""
                update generated_documents
                   set status = :status, content = cast(:content as jsonb), changes = cast(:changes as jsonb),
                       fact_check = cast(:factCheck as jsonb), version = version + 1, updated_at = now()
                 where id = :id
                """).param("id", id).param("status", status.name()).param("content", write(content))
                .param("changes", write(changes)).param("factCheck", write(factCheck)).update();
    }

    void approve(UUID id, FactCheck factCheck) {
        jdbc.sql("""
                update generated_documents
                   set status = 'APPROVED', fact_check = cast(:factCheck as jsonb), version = version + 1,
                       updated_at = now(), approved_at = now()
                 where id = :id
                """).param("id", id).param("factCheck", write(factCheck)).update();
    }

    Optional<Row> find(UUID userId, UUID id, boolean forUpdate) {
        return jdbc.sql("select " + COLUMNS + " from generated_documents where id = :id and user_id = :userId"
                + (forUpdate ? " for update" : "")).param("id", id).param("userId", userId)
                .query((rs, row) -> row(rs)).optional();
    }

    /** The open (GENERATING, DRAFT or FACT_CHECK_FAILED) draft for this key, if any. */
    Optional<Row> findOpen(UUID userId, UUID jobId, UUID versionId, boolean forUpdate) {
        return jdbc.sql("select " + COLUMNS + """
                 from generated_documents
                 where user_id = :userId and job_id = :jobId and base_resume_version_id = :versionId
                   and type = 'TAILORED_RESUME' and status <> 'APPROVED'
                """ + (forUpdate ? " for update" : "")).param("userId", userId).param("jobId", jobId)
                .param("versionId", versionId).query((rs, row) -> row(rs)).optional();
    }

    List<Row> list(UUID userId, UUID jobId, DocumentStatus status, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                 from generated_documents
                 where user_id = :userId and (cast(:jobId as uuid) is null or job_id = :jobId)
                   and (cast(:status as text) is null or status = :status)
                 order by created_at desc, id
                 limit :limit
                """).param("userId", userId).param("jobId", jobId).param("status", status == null ? null : status.name())
                .param("limit", limit).query((rs, row) -> row(rs)).list();
    }

    /** Deletes a draft; false if there is no such draft (an APPROVED row is refused by the trigger). */
    boolean deleteDraft(UUID userId, UUID id) {
        return jdbc.sql("delete from generated_documents where id = :id and user_id = :userId and status <> 'APPROVED'")
                .param("id", id).param("userId", userId).update() == 1;
    }

    void deleteStale(UUID id) {
        jdbc.sql("delete from generated_documents where id = :id and status = 'GENERATING'").param("id", id).update();
    }

    /** Account deletion: the one place that may remove approved documents (see V25's trigger). */
    void purgeAll(UUID userId) {
        jdbc.sql("select set_config('app.purge_documents', 'on', true)").query(String.class).single();
        jdbc.sql("delete from generated_documents where user_id = :userId").param("userId", userId).update();
    }

    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            String content = rs.getString("content");
            String factCheck = rs.getString("fact_check");
            return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                    DocumentType.valueOf(rs.getString("type")), DocumentStatus.valueOf(rs.getString("status")),
                    rs.getObject("job_id", UUID.class), rs.getString("job_title"), rs.getString("job_company"),
                    rs.getObject("base_resume_version_id", UUID.class), rs.getString("prompt_version"),
                    rs.getString("model"), json.readTree(rs.getString("source_content")),
                    content == null ? null : json.readTree(content),
                    json.readValue(rs.getString("changes"), CHANGES),
                    factCheck == null ? null : json.readValue(factCheck, FactCheck.class), rs.getInt("version"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                    rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored document JSON is unreadable", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise document JSON", e);
        }
    }
}
