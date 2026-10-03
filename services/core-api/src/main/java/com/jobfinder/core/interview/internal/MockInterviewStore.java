package com.jobfinder.core.interview.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.interview.MockInterviews.FeedbackView;
import com.jobfinder.core.interview.MockInterviews.PersonaView;
import com.jobfinder.core.interview.MockInterviews.SummaryView;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SQL of {@code interview_sessions} and {@code interview_turns}. Every read and write that starts from a session id
 * also takes the owner's user id: a session is only ever found through its owner.
 */
@Repository
class MockInterviewStore {

    /** One session row, without its turns. */
    record SessionRow(UUID id, UUID userId, UUID jobId, String jobTitle, String jobCompany, UUID applicationId,
            UUID prepId, PersonaView persona, String status, int maxTurns, int turnsAnswered,
            BigDecimal creditsConsumed, String promptVersion, SummaryView summary, Instant createdAt,
            Instant completedAt) {

        boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** One turn. {@code feedback} is set on a candidate turn; {@code category} and {@code source} on an interviewer turn. */
    record TurnRow(UUID id, int position, String role, String content, String category, String source,
            FeedbackView feedback, String idempotencyKey, Instant createdAt) {

        boolean interviewer() {
            return "INTERVIEWER".equals(role);
        }
    }

    private static final String SESSION_COLUMNS = """
            id, user_id, job_id, job_title, job_company, application_id, prep_id, persona::text as persona, status,
            max_turns, turns_answered, credits_consumed, prompt_version, summary::text as summary, created_at,
            completed_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    MockInterviewStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // --- sessions ---

    void insertSession(UUID id, UUID userId, UUID jobId, String jobTitle, String jobCompany, UUID applicationId,
            UUID prepId, PersonaView persona, int maxTurns, String promptVersion, BigDecimal credits, Instant now) {
        jdbc.sql("""
                insert into interview_sessions (id, user_id, job_id, job_title, job_company, application_id, prep_id,
                        mode, persona, status, max_turns, turns_answered, credits_consumed, prompt_version,
                        last_activity_at, created_at)
                values (:id, :userId, :jobId, :jobTitle, :jobCompany, :applicationId, :prepId, 'MOCK',
                        cast(:persona as jsonb), 'ACTIVE', :maxTurns, 0, :credits, :promptVersion, :now, :now)
                """).param("id", id).param("userId", userId).param("jobId", jobId).param("jobTitle", jobTitle)
                .param("jobCompany", jobCompany).param("applicationId", applicationId).param("prepId", prepId)
                .param("persona", write(persona)).param("maxTurns", maxTurns).param("credits", credits)
                .param("promptVersion", promptVersion).param("now", Timestamp.from(now)).update();
    }

    Optional<SessionRow> find(UUID userId, UUID id) {
        return jdbc.sql("select " + SESSION_COLUMNS + " from interview_sessions where id = :id and user_id = :userId")
                .param("id", id).param("userId", userId).query(this::session).optional();
    }

    /** The user's ACTIVE session for the job, if any (at most one: see V31). */
    Optional<SessionRow> findActiveForJob(UUID userId, UUID jobId) {
        return jdbc.sql("select " + SESSION_COLUMNS
                + " from interview_sessions where user_id = :userId and job_id = :jobId and status = 'ACTIVE'")
                .param("userId", userId).param("jobId", jobId).query(this::session).optional();
    }

    List<SessionRow> page(UUID userId, int limit, long offset) {
        return jdbc.sql("select " + SESSION_COLUMNS + """
                 from interview_sessions where user_id = :userId
                 order by created_at desc, id desc limit :limit offset :offset
                """).param("userId", userId).param("limit", limit).param("offset", offset).query(this::session).list();
    }

    long count(UUID userId) {
        return jdbc.sql("select count(*) from interview_sessions where user_id = :userId").param("userId", userId)
                .query(Long.class).single();
    }

    /**
     * Turns the user's ACTIVE sessions with no activity since {@code cutoff} into ABANDONED. A session with a live
     * in-flight request (taken after {@code staleCutoff}) is left alone: it is being worked on.
     */
    int abandonStale(UUID userId, Instant cutoff, Instant staleCutoff) {
        return jdbc.sql("""
                update interview_sessions set status = 'ABANDONED'
                 where user_id = :userId and status = 'ACTIVE' and last_activity_at < :cutoff
                   and (in_flight_since is null or in_flight_since < :staleCutoff)
                """).param("userId", userId).param("cutoff", Timestamp.from(cutoff))
                .param("staleCutoff", Timestamp.from(staleCutoff)).update();
    }

    /**
     * Takes the session's single in-flight slot. True when this caller now holds it; false when the session is not
     * ACTIVE or another request holds it (one taken before {@code staleCutoff} belongs to a dead request and is taken
     * over).
     */
    boolean acquire(UUID userId, UUID id, String key, Instant now, Instant staleCutoff) {
        return jdbc.sql("""
                update interview_sessions set in_flight_since = :now, in_flight_key = :key
                 where id = :id and user_id = :userId and status = 'ACTIVE'
                   and (in_flight_since is null or in_flight_since < :staleCutoff)
                """).param("id", id).param("userId", userId).param("key", key).param("now", Timestamp.from(now))
                .param("staleCutoff", Timestamp.from(staleCutoff)).update() == 1;
    }

    /** Gives the slot back, if this caller still holds it. */
    void release(UUID id, String key) {
        jdbc.sql("""
                update interview_sessions set in_flight_since = null, in_flight_key = null
                 where id = :id and in_flight_key = :key
                """).param("id", id).param("key", key).update();
    }

    /** Adds credits the session's recorded calls cost (a call that was billed and then discarded counts too). */
    void addCredits(UUID id, BigDecimal credits) {
        if (credits.signum() > 0) {
            jdbc.sql("update interview_sessions set credits_consumed = credits_consumed + :credits where id = :id")
                    .param("id", id).param("credits", credits).update();
        }
    }

    /**
     * One more answered turn, in the caller's transaction. {@code release} gives the in-flight slot back (false on the
     * last turn, where the same request goes on to make the summary). False when the caller no longer holds the slot
     * (it was taken over as stale): the caller's transaction must then roll back.
     */
    boolean recordAnswered(UUID id, String key, BigDecimal credits, Instant now, boolean release) {
        return jdbc.sql("""
                update interview_sessions
                   set turns_answered = turns_answered + 1, credits_consumed = credits_consumed + :credits,
                       last_activity_at = :now,
                       in_flight_since = case when :release then null else in_flight_since end,
                       in_flight_key = case when :release then null else in_flight_key end
                 where id = :id and in_flight_key = :key and status = 'ACTIVE' and turns_answered < max_turns
                """).param("id", id).param("key", key).param("credits", credits).param("now", Timestamp.from(now))
                .param("release", release).update() == 1;
    }

    /** Completes the session with its summary and gives the slot back, in the caller's transaction. */
    boolean complete(UUID id, String key, SummaryView summary, BigDecimal credits, Instant now) {
        return jdbc.sql("""
                update interview_sessions
                   set status = 'COMPLETED', summary = cast(:summary as jsonb), completed_at = :now,
                       credits_consumed = credits_consumed + :credits, last_activity_at = :now,
                       in_flight_since = null, in_flight_key = null
                 where id = :id and in_flight_key = :key and status = 'ACTIVE'
                """).param("id", id).param("key", key).param("summary", write(summary)).param("credits", credits)
                .param("now", Timestamp.from(now)).update() == 1;
    }

    /** Everything of the user's, for account deletion (turns go with their session). */
    void purgeAll(UUID userId) {
        jdbc.sql("delete from interview_sessions where user_id = :userId").param("userId", userId).update();
    }

    // --- turns ---

    void insertInterviewerTurn(UUID sessionId, int position, String content, String category, String source,
            Instant now) {
        jdbc.sql("""
                insert into interview_turns (id, session_id, position, role, content, category, question_source,
                        created_at)
                values (:id, :sessionId, :position, 'INTERVIEWER', :content, :category, :source, :now)
                """).param("id", UUID.randomUUID()).param("sessionId", sessionId).param("position", position)
                .param("content", content).param("category", category).param("source", source)
                .param("now", Timestamp.from(now)).update();
    }

    void insertCandidateTurn(UUID sessionId, int position, String content, FeedbackView feedback, String key,
            Instant now) {
        jdbc.sql("""
                insert into interview_turns (id, session_id, position, role, content, feedback, idempotency_key,
                        created_at)
                values (:id, :sessionId, :position, 'CANDIDATE', :content, cast(:feedback as jsonb), :key, :now)
                """).param("id", UUID.randomUUID()).param("sessionId", sessionId).param("position", position)
                .param("content", content).param("feedback", write(feedback)).param("key", key)
                .param("now", Timestamp.from(now)).update();
    }

    List<TurnRow> turns(UUID sessionId) {
        return jdbc.sql("""
                select id, position, role, content, category, question_source, feedback::text as feedback,
                       idempotency_key, created_at
                  from interview_turns where session_id = :sessionId order by position
                """).param("sessionId", sessionId).query(this::turn).list();
    }

    Optional<TurnRow> turnByKey(UUID sessionId, String key) {
        return jdbc.sql("""
                select id, position, role, content, category, question_source, feedback::text as feedback,
                       idempotency_key, created_at
                  from interview_turns where session_id = :sessionId and idempotency_key = :key
                """).param("sessionId", sessionId).param("key", key).query(this::turn).optional();
    }

    // --- rows ---

    private SessionRow session(ResultSet rs, int n) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        String summary = rs.getString("summary");
        return new SessionRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getString("job_title"), rs.getString("job_company"),
                rs.getObject("application_id", UUID.class), rs.getObject("prep_id", UUID.class),
                read(rs.getString("persona"), PersonaView.class), rs.getString("status"), rs.getInt("max_turns"),
                rs.getInt("turns_answered"), rs.getBigDecimal("credits_consumed"), rs.getString("prompt_version"),
                summary == null ? null : read(summary, SummaryView.class), rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private TurnRow turn(ResultSet rs, int n) throws SQLException {
        String feedback = rs.getString("feedback");
        return new TurnRow(rs.getObject("id", UUID.class), rs.getInt("position"), rs.getString("role"),
                rs.getString("content"), rs.getString("category"), rs.getString("question_source"),
                feedback == null ? null : read(feedback, FeedbackView.class), rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot write mock interview JSON", e);
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored mock interview JSON is unreadable", e);
        }
    }
}
