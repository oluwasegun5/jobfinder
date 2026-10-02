package com.jobfinder.core.documents.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.documents.internal.DocumentDtos.DocumentType;
import com.jobfinder.core.documents.internal.DocumentDtos.DraftResponse;
import com.jobfinder.core.documents.internal.DocumentService.Outcome;
import com.jobfinder.core.documents.internal.PackService.Result;
import com.jobfinder.core.documents.internal.WritingDtos.PackList;
import com.jobfinder.core.documents.internal.WritingDtos.PackRequest;
import com.jobfinder.core.documents.internal.WritingDtos.PackResponse;
import com.jobfinder.core.documents.internal.WritingDtos.RetryRequest;
import com.jobfinder.core.documents.internal.WritingDtos.WritingRequest;
import com.jobfinder.core.identity.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Cover letters, screening answers and the application pack (docs/adr/0031-cover-letters-and-application-pack.md).
 * Generated letters and answers are ordinary documents: read them with {@code GET /documents/{id}}, edit them with
 * {@code PATCH /documents/{id}}, approve them with {@code POST /documents/{id}/approve}. Every endpoint needs a
 * signed-in user and acts on that user's data only: someone else's document or pack is a 404, never a 403.
 */
@RestController
class WritingController {

    private final WritingService writing;
    private final DocumentService documents;
    private final PackService packs;

    WritingController(WritingService writing, DocumentService documents, PackService packs) {
        this.writing = writing;
        this.documents = documents;
        this.packs = packs;
    }

    /**
     * Writes a cover letter for the job from the caller's primary resume: 201 when it was made, 200 when an open
     * draft with the same tone, length and notes (or one being made) already exists. Other options make a new draft
     * and keep the old one as history ({@code SUPERSEDED}); an approved letter is never touched. The draft is
     * {@code FACT_CHECK_FAILED} when the text claims something the resume does not show. 409 {@code resume_required},
     * 404 {@code job_not_found}, 429 {@code ai_daily_cap_reached}, 503 {@code writing_unavailable}.
     */
    @PostMapping("/jobs/{id}/cover-letter")
    ResponseEntity<DraftResponse> coverLetter(@PathVariable UUID id,
            @RequestBody(required = false) @Valid WritingRequest body) {
        return respond(writing.generate(CurrentUser.require().id(), id, DocumentType.COVER_LETTER,
                body == null ? WritingRequest.defaults() : body));
    }

    /**
     * Answers the fixed set of common screening questions for the job, with the same options and outcomes as the
     * cover letter. Factual answers (notice period, salary, work authorization, location, years of experience) come
     * from the profile and preferences; one the profile cannot answer is {@code NEEDS_INPUT} and has to be written
     * (PATCH) before the set can be approved.
     */
    @PostMapping("/jobs/{id}/screening-answers")
    ResponseEntity<DraftResponse> screeningAnswers(@PathVariable UUID id,
            @RequestBody(required = false) @Valid WritingRequest body) {
        return respond(writing.generate(CurrentUser.require().id(), id, DocumentType.SCREENING_ANSWERS,
                body == null ? WritingRequest.defaults() : body));
    }

    private ResponseEntity<DraftResponse> respond(Outcome outcome) {
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(documents.toResponse(outcome.row()));
    }

    /**
     * Makes the application pack of the job: the tailored CV (an existing one for this job and resume is reused), the
     * cover letter and the screening answers, in one call. 201 when the pack was created, 200 when it existed. A part
     * that fails or finds the daily allowance used up is reported as {@code FAILED} or {@code BLOCKED_BY_CAP} with a
     * typed error and the other parts are kept: the answer is still a success, see {@code status}
     * ({@code COMPLETE}, {@code PARTIAL}, {@code FAILED}). The same request again returns the pack; other options
     * write the letter and the answers again. Retry what is missing with {@code POST /application-packs/{id}/retry}.
     */
    @PostMapping("/jobs/{id}/application-pack")
    ResponseEntity<PackResponse> createPack(@PathVariable UUID id, @RequestBody(required = false) @Valid PackRequest body) {
        UUID userId = CurrentUser.require().id();
        Result result = packs.create(userId, id, body == null ? new PackRequest(null, null, null, null) : body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(packs.toResponse(userId, result.pack()));
    }

    @GetMapping("/application-packs/{id}")
    PackResponse getPack(@PathVariable UUID id) {
        UUID userId = CurrentUser.require().id();
        return packs.toResponse(userId, packs.get(userId, id));
    }

    /** The caller's packs, newest first, optionally those of one job. */
    @GetMapping("/application-packs")
    PackList listPacks(@RequestParam(required = false) UUID jobId,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return packs.toList(packs.list(CurrentUser.require().id(), jobId, limit));
    }

    /**
     * Makes the parts that failed, were blocked by the daily cap or lost their draft again (or the ones named in
     * {@code parts}). A pack with nothing to retry is returned unchanged.
     */
    @PostMapping("/application-packs/{id}/retry")
    PackResponse retryPack(@PathVariable UUID id, @RequestBody(required = false) @Valid RetryRequest body) {
        UUID userId = CurrentUser.require().id();
        return packs.toResponse(userId, packs.retry(userId, id, body));
    }
}
