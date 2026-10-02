package com.jobfinder.core.interview.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.interview.InterviewPrep;
import com.jobfinder.core.interview.InterviewPrep.Generated;
import com.jobfinder.core.interview.InterviewPrep.InterviewPrepView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Interview prep (docs/adr/0033-interview-prep.md). Every endpoint needs a signed-in user and acts on that user's data
 * only: someone else's prep is a 404, never a 403.
 */
@RestController
class InterviewController {

    /** The job to prepare for. */
    record GeneratePrepRequest(@NotNull UUID jobId) {
    }

    private final InterviewPrep interviewPrep;

    InterviewController(InterviewPrep interviewPrep) {
        this.interviewPrep = interviewPrep;
    }

    /**
     * The caller's interview prep for the job: 201 when it was made, 200 when it already existed (the stored result is
     * returned, no model is called and nothing is charged to the daily cap; a prep another request is still making comes
     * back with status {@code GENERATING}, read it again with GET). It holds likely questions by category and a
     * company brief made only from the job posting and the company record, where every claim names its source field and
     * what the fields do not say is listed under {@code unknowns}. 404 {@code job_not_found}, 409
     * {@code resume_required}, 429 {@code ai_daily_cap_reached}, 503
     * {@code interview_prep_unavailable}.
     */
    @PostMapping("/interview-prep")
    ResponseEntity<InterviewPrepView> generate(@RequestBody @Valid GeneratePrepRequest body) {
        Generated result = interviewPrep.generate(CurrentUser.require().id(), body.jobId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.prep());
    }

    /** One of the caller's preps. 404 {@code interview_prep_not_found} if it does not exist or is not theirs. */
    @GetMapping("/interview-prep/{id}")
    InterviewPrepView get(@PathVariable UUID id) {
        return interviewPrep.get(CurrentUser.require().id(), id);
    }
}
