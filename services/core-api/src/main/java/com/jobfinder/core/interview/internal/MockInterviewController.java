package com.jobfinder.core.interview.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.interview.MockInterviews;
import com.jobfinder.core.interview.MockInterviews.AnswerResult;
import com.jobfinder.core.interview.MockInterviews.SessionPage;
import com.jobfinder.core.interview.MockInterviews.SessionView;
import com.jobfinder.core.interview.MockInterviews.StartResult;
import com.jobfinder.core.interview.MockInterviews.StartCommand;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Text mock interviews (docs/adr/0034-mock-interview.md). Every endpoint needs a signed-in user and acts on that user's
 * sessions only: someone else's session is a 404, never a 403.
 */
@RestController
class MockInterviewController {

    /**
     * Start a session for a job. {@code applicationId} ties it to one of the caller's applications, {@code prepId} to
     * one of their interview preps for the same job (the questions are then taken from it), {@code maxTurns} asks for
     * fewer questions than the configured limit.
     */
    record StartSessionRequest(@NotNull UUID jobId, UUID applicationId, UUID prepId, @Min(1) @Max(20) Integer maxTurns) {
    }

    /**
     * The answer to the open question, and a key the client makes up for this answer (any 8 to 100 letters, digits,
     * dashes or underscores): sending the same key again returns the stored result and charges nothing.
     */
    record AnswerRequest(@NotBlank @Size(max = 20000) String answer,
            @NotBlank @Size(min = 8, max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String idempotencyKey) {
    }

    private final MockInterviews sessions;

    MockInterviewController(MockInterviews sessions) {
        this.sessions = sessions;
    }

    /**
     * Starts a mock interview and returns it with its first question ({@code openQuestion}) and the interviewer persona
     * chosen from the job. 201. Idempotent per job: when the caller already has an open (ACTIVE, not abandoned) session
     * for the job, that session is returned with 200, no model call and no charge (the application, prep and
     * {@code maxTurns} of this request are ignored); a COMPLETED or ABANDONED one never blocks a new start. 404 {@code job_not_found} / {@code application_not_found} /
     * {@code interview_prep_not_found}, 409 {@code application_job_mismatch} / {@code prep_job_mismatch}, 429
     * {@code ai_daily_cap_reached} (only when no prep is given: the first question then costs a model call), 503
     * {@code mock_interview_unavailable}.
     */
    @Operation(operationId = "startInterviewSession", responses = {
            @ApiResponse(responseCode = "201", description = "A new session was started",
                    content = @Content(schema = @Schema(implementation = SessionView.class))),
            @ApiResponse(responseCode = "200",
                    description = "The caller already had an open session for the job: it is returned as it is, with no "
                            + "model call and no charge",
                    content = @Content(schema = @Schema(implementation = SessionView.class))) })
    @PostMapping("/interview-sessions")
    ResponseEntity<SessionView> start(@RequestBody @Valid StartSessionRequest body) {
        StartResult result = sessions.start(CurrentUser.require().id(),
                new StartCommand(body.jobId(), body.applicationId(), body.prepId(), body.maxTurns()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.session());
    }

    /** One of the caller's sessions with its transcript. 404 {@code interview_session_not_found}. */
    @Operation(operationId = "getInterviewSession")
    @GetMapping("/interview-sessions/{id}")
    SessionView get(@PathVariable UUID id) {
        return sessions.get(CurrentUser.require().id(), id);
    }

    /** The caller's sessions, newest first, without transcripts. */
    @Operation(operationId = "listInterviewSessions")
    @GetMapping("/interview-sessions")
    SessionPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return sessions.list(CurrentUser.require().id(), page, size);
    }

    /**
     * Answers the open question. Returns the stored turn with its rubric feedback (structure, relevance, specificity,
     * STAR, overall, strengths and improvements with quotes), the next question, and after the last turn the session
     * summary. The same {@code idempotencyKey} again returns the stored result with no model call and no charge.
     * 400 {@code answer_too_long}, 404 {@code interview_session_not_found}, 409 {@code answer_in_flight} (another answer
     * is being processed) / {@code interview_session_completed} / {@code interview_session_abandoned} /
     * {@code turn_limit_reached} / {@code idempotency_key_reused}, 429 {@code ai_daily_cap_reached}, 503
     * {@code mock_interview_unavailable}.
     */
    @Operation(operationId = "answerInterviewSession")
    @PostMapping("/interview-sessions/{id}/answers")
    AnswerResult answer(@PathVariable UUID id, @RequestBody @Valid AnswerRequest body) {
        return sessions.answer(CurrentUser.require().id(), id, body.answer(), body.idempotencyKey());
    }

    /**
     * Ends the session and makes its summary (averages per rubric dimension, top strengths and improvements, three next
     * steps, and the credits it consumed). Safe to repeat: a completed session is returned as it is. 404, 409
     * {@code interview_session_abandoned} / {@code nothing_to_summarise} / {@code answer_in_flight}, 429, 503.
     */
    @Operation(operationId = "completeInterviewSession")
    @PostMapping("/interview-sessions/{id}/complete")
    SessionView complete(@PathVariable UUID id) {
        return sessions.complete(CurrentUser.require().id(), id);
    }
}
