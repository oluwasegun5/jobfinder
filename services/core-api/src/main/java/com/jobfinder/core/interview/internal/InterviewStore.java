package com.jobfinder.core.interview.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.interview.InterviewPrep.BriefSectionView;
import com.jobfinder.core.interview.InterviewPrep.CompanyBriefView;
import com.jobfinder.core.interview.InterviewPrep.InterviewQuestionView;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SQL of {@code interview_prep}, {@code interview_questions} and {@code company_briefs}. Every read and write takes
 * the owner's user id: a prep is only ever found through its owner.
 */
@Repository
class InterviewStore {

    /** One prep row, without its questions and brief. {@code model} and {@code briefModel} are null while GENERATING. */
    record Row(UUID id, UUID userId, UUID jobId, String jobTitle, String jobCompany, String status,
            String promptVersion, String model, String briefModel, int questionsDropped, Instant createdAt) {

        boolean ready() {
            return "READY".equals(status);
        }
    }

    private static final TypeReference<List<BriefSectionView>> SECTIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, user_id, job_id, job_title, job_company, status, prompt_version, model, brief_model,
            questions_dropped, created_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    InterviewStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<Row> find(UUID userId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from interview_prep where id = :id and user_id = :userId")
                .param("id", id).param("userId", userId).query(InterviewStore::row).optional();
    }

    Optional<Row> findByKey(UUID userId, UUID jobId, String promptVersion) {
        return jdbc.sql("select " + COLUMNS + """
                 from interview_prep
                 where user_id = :userId and job_id = :jobId and prompt_version = :promptVersion
                """).param("userId", userId).param("jobId", jobId).param("promptVersion", promptVersion)
                .query(InterviewStore::row).optional();
    }

    /**
     * Inserts the GENERATING placeholder. Throws DuplicateKeyException if the user already has a prep (made or being
     * made) for this job and prompt version: the unique constraint is what makes a double click one model call.
     */
    void insertPlaceholder(UUID id, UUID userId, UUID jobId, String jobTitle, String jobCompany,
            String promptVersion) {
        jdbc.sql("""
                insert into interview_prep (id, user_id, job_id, job_title, job_company, status, prompt_version,
                        created_at, updated_at)
                values (:id, :userId, :jobId, :jobTitle, :jobCompany, 'GENERATING', :promptVersion, now(), now())
                """).param("id", id).param("userId", userId).param("jobId", jobId).param("jobTitle", jobTitle)
                .param("jobCompany", jobCompany).param("promptVersion", promptVersion).update();
    }

    /** Deletes a GENERATING placeholder (its request failed, or died). A READY prep is never touched. */
    void deletePlaceholder(UUID id) {
        jdbc.sql("delete from interview_prep where id = :id and status = 'GENERATING'").param("id", id).update();
    }

    /** Deletes the placeholders of this key that are older than {@code cutoff}: their request died. */
    void deleteAbandoned(UUID userId, UUID jobId, String promptVersion, Instant cutoff) {
        jdbc.sql("""
                delete from interview_prep
                 where user_id = :userId and job_id = :jobId and prompt_version = :promptVersion
                   and status = 'GENERATING' and created_at < :cutoff
                """).param("userId", userId).param("jobId", jobId).param("promptVersion", promptVersion)
                .param("cutoff", java.sql.Timestamp.from(cutoff)).update();
    }

    /**
     * Fills the placeholder with the questions and the brief and marks it READY, in the caller's transaction. False if
     * the placeholder is gone (deleted as abandoned while the model worked).
     */
    boolean complete(UUID id, String model, String briefModel, int questionsDropped,
            List<InterviewQuestionView> questions, List<BriefSectionView> sections, List<String> unknowns,
            int droppedClaims) {
        int updated = jdbc.sql("""
                update interview_prep
                   set status = 'READY', model = :model, brief_model = :briefModel,
                       questions_dropped = :questionsDropped, updated_at = now()
                 where id = :id and status = 'GENERATING'
                """).param("id", id).param("model", model).param("briefModel", briefModel)
                .param("questionsDropped", questionsDropped).update();
        if (updated != 1) {
            return false;
        }
        int position = 0;
        for (InterviewQuestionView q : questions) {
            jdbc.sql("""
                    insert into interview_questions (id, prep_id, position, category, question, rationale, difficulty)
                    values (:id, :prepId, :position, :category, :question, :rationale, :difficulty)
                    """).param("id", UUID.randomUUID()).param("prepId", id).param("position", position++)
                    .param("category", q.category()).param("question", q.question())
                    .param("rationale", q.rationale()).param("difficulty", q.difficulty()).update();
        }
        jdbc.sql("""
                insert into company_briefs (id, prep_id, model, sections, unknowns, dropped_claims, created_at)
                values (:id, :prepId, :model, cast(:sections as jsonb), cast(:unknowns as jsonb), :dropped, now())
                """).param("id", UUID.randomUUID()).param("prepId", id).param("model", briefModel)
                .param("sections", write(sections)).param("unknowns", write(unknowns)).param("dropped", droppedClaims)
                .update();
        return true;
    }

    List<InterviewQuestionView> questions(UUID prepId) {
        return jdbc.sql("""
                select category, question, rationale, difficulty from interview_questions
                 where prep_id = :prepId order by position
                """).param("prepId", prepId).query((rs, n) -> new InterviewQuestionView(rs.getString("category"),
                rs.getString("question"), rs.getString("rationale"), rs.getString("difficulty"))).list();
    }

    Optional<CompanyBriefView> brief(UUID prepId) {
        return jdbc.sql("""
                select model, sections::text as sections, unknowns::text as unknowns, dropped_claims
                  from company_briefs where prep_id = :prepId
                """).param("prepId", prepId).query((rs, n) -> new CompanyBriefView(rs.getString("model"),
                read(rs.getString("sections"), SECTIONS), read(rs.getString("unknowns"), STRINGS),
                rs.getInt("dropped_claims"))).optional();
    }

    /** Everything of the user's, for account deletion (questions and briefs go with their prep). */
    void purgeAll(UUID userId) {
        jdbc.sql("delete from interview_prep where user_id = :userId").param("userId", userId).update();
    }

    private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getString("job_title"), rs.getString("job_company"),
                rs.getString("status"), rs.getString("prompt_version"), rs.getString("model"),
                rs.getString("brief_model"), rs.getInt("questions_dropped"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot write interview prep JSON", e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored interview prep JSON is unreadable", e);
        }
    }
}
