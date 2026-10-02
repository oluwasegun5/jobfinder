package com.jobfinder.core.interview;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Text mock interviews (docs/adr/0034-mock-interview.md): a session of turns in which an interviewer asks a question,
 * the candidate answers, the system returns rubric feedback for that answer and the next question, and a summary is
 * made at the end. Every call is scoped to the user it is called for: another user's session is never found, so it is
 * "not found" and never "forbidden".
 *
 * <p>The candidate's answers, the job text and the model's output are untrusted. Nothing here logs an answer.
 */
public interface MockInterviews {

    /**
     * Starts a session for the job. The first question comes from the stored prep when {@code prepId} is given (no
     * model call), else from a model.
     *
     * @throws com.jobfinder.core.shared.ApiException {@code job_not_found}, {@code application_not_found},
     *         {@code application_job_mismatch}, {@code interview_prep_not_found}, {@code prep_job_mismatch},
     *         {@code mock_interview_unavailable} (503); the daily cap ({@code ai_daily_cap_reached}, 429) is raised by
     *         the billing module
     */
    SessionView start(UUID userId, StartCommand command);

    /** @throws com.jobfinder.core.shared.ApiException {@code interview_session_not_found} (404) if it is not the user's */
    SessionView get(UUID userId, UUID sessionId);

    /**
     * The user's answer to the open question. A repeat of the same {@code idempotencyKey} returns the stored result,
     * calls no model and charges nothing. After the last turn the session completes in the same call (the summary is
     * in the result unless the summary call failed; then the session stays ACTIVE and {@link #complete} retries it).
     *
     * @throws com.jobfinder.core.shared.ApiException {@code interview_session_not_found} (404),
     *         {@code answer_too_long} (400), {@code answer_in_flight} (409: another answer or completion of this
     *         session is running), {@code interview_session_completed} / {@code interview_session_abandoned} (409),
     *         {@code idempotency_key_reused} (409: the key belongs to a different answer),
     *         {@code mock_interview_unavailable} (503); the daily cap (429) is raised by the billing module
     */
    AnswerResult answer(UUID userId, UUID sessionId, String answer, String idempotencyKey);

    /**
     * Ends the session and makes the summary from what was answered. Idempotent: a completed session is returned as it
     * is, with no model call.
     *
     * @throws com.jobfinder.core.shared.ApiException {@code interview_session_not_found} (404),
     *         {@code interview_session_abandoned} (409), {@code nothing_to_summarise} (409: no answer yet),
     *         {@code answer_in_flight} (409), {@code mock_interview_unavailable} (503); the daily cap (429)
     */
    SessionView complete(UUID userId, UUID sessionId);

    /** The user's sessions, newest first, without transcripts. */
    SessionPage list(UUID userId, int page, int size);

    /** What the user asks for when starting a session. {@code maxTurns} is optional and never above the configured limit. */
    record StartCommand(UUID jobId, UUID applicationId, UUID prepId, Integer maxTurns) {
    }

    /** The interviewer, chosen once at start from the job. A persona changes tone and question style, never the rubric. */
    record PersonaView(String interviewer, String function, String seniority, String tone, String questionStyle) {
    }

    /**
     * A session. {@code status} is {@code ACTIVE}, {@code COMPLETED} or {@code ABANDONED}; {@code creditsConsumed} is what
     * its recorded AI calls cost in credits. {@code openQuestion} is the question waiting for an answer (null once the
     * session is over, and after the last answer while the summary is pending). {@code summary} is null until completion.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionView(UUID id, UUID jobId, String jobTitle, String jobCompany, UUID applicationId, UUID prepId,
            String mode, PersonaView persona, String status, int maxTurns, int turnsAnswered,
            BigDecimal creditsConsumed, String promptVersion, Instant createdAt, Instant completedAt,
            TurnView openQuestion, List<TurnView> turns, SummaryView summary) {
    }

    /**
     * One turn. An interviewer turn has a {@code category} ({@code behavioral}, {@code technical}, {@code role_specific})
     * and a {@code source} ({@code PREP} or {@code GENERATED}); a candidate turn has {@code feedback}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TurnView(int position, String role, String content, String category, String source,
            FeedbackView feedback, Instant createdAt) {
    }

    /** Rubric scores are whole numbers from 1 to 5. {@code evidence} are the quotes behind the points below. */
    record FeedbackView(int structure, int relevance, int specificity, StarView star, int overall,
            List<PointView> strengths, List<PointView> improvements, List<String> evidence) {
    }

    /** All fields are null for a question that is not behavioural; otherwise a score and the four components. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StarView(Integer score, Boolean situation, Boolean task, Boolean action, Boolean result) {
    }

    /** {@code quote} is verbatim from the answer (case and spacing aside); an improvement may have none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PointView(String text, String quote) {
    }

    /** Means over the answered turns, computed here and never by the model; star is null with no behavioural turn. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AveragesView(double structure, double relevance, double specificity, Double starCompleteness,
            double overall) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SummaryView(int turnsAnswered, AveragesView averages, List<String> topStrengths,
            List<String> topImprovements, String narrative, List<String> nextSteps, String model,
            BigDecimal creditsConsumed) {
    }

    /**
     * The result of an answer: the stored candidate turn with its feedback, the next question (null after the last turn
     * or when the session is over), the summary when the session completed, and the session as it now is.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnswerResult(TurnView turn, TurnView nextQuestion, SummaryView summary, SessionView session) {
    }

    /** A row of the history. {@code overall} is the session's average overall score, once it has a summary. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionSummary(UUID id, UUID jobId, String jobTitle, String jobCompany, UUID applicationId, UUID prepId,
            String status, int maxTurns, int turnsAnswered, BigDecimal creditsConsumed, Double overall,
            Instant createdAt, Instant completedAt) {
    }

    record SessionPage(List<SessionSummary> items, int page, int size, long totalElements, int totalPages) {
    }
}
