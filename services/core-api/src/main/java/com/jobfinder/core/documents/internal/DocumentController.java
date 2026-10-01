package com.jobfinder.core.documents.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.documents.internal.DocumentDtos.DocumentStatus;
import com.jobfinder.core.documents.internal.DocumentDtos.DraftResponse;
import com.jobfinder.core.documents.internal.DocumentDtos.ListResponse;
import com.jobfinder.core.documents.internal.DocumentDtos.PatchRequest;
import com.jobfinder.core.documents.internal.DocumentDtos.TailorRequest;
import com.jobfinder.core.documents.internal.DocumentService.Outcome;
import com.jobfinder.core.identity.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Tailored resume drafts (docs/adr/0029-resume-tailoring.md). Every endpoint needs a signed-in user and acts on that
 * user's documents only: someone else's document is a 404, never a 403.
 */
@RestController
class DocumentController {

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /**
     * Tailors the caller's primary resume to the job and returns the draft: 201 when it was just made, 200 when an
     * open draft for the same job and resume version already existed (nothing is regenerated and no allowance is
     * spent; delete the draft to start over). The draft is {@code FACT_CHECK_FAILED}, with its flags, if the model
     * invented something. 409 {@code resume_required} without a parsed primary resume, 404 for an unknown job, 429
     * {@code ai_daily_cap_reached} when the daily AI allowance is used up, 503 when ai-service is unavailable.
     */
    @PostMapping("/jobs/{id}/tailor")
    ResponseEntity<DraftResponse> tailor(@PathVariable UUID id, @RequestBody(required = false) @Valid TailorRequest body) {
        Outcome outcome = documents.tailor(CurrentUser.require().id(), id, body == null ? null : body.options());
        DraftResponse response = documents.toResponse(outcome.row());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    /** The caller's documents, newest first, optionally for one job or in one status. */
    @GetMapping("/documents")
    ListResponse list(@RequestParam(required = false) UUID jobId, @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return documents.toList(documents.list(CurrentUser.require().id(), jobId, status, limit));
    }

    @GetMapping("/documents/{id}")
    DraftResponse get(@PathVariable UUID id) {
        return documents.toResponse(documents.get(CurrentUser.require().id(), id));
    }

    /**
     * Accepts or rejects changes and edits their text, then re-runs the fact check on the result. 409
     * {@code version_conflict} if {@code version} is not the draft's current one, 409 {@code document_approved} for
     * an approved document.
     */
    @PatchMapping("/documents/{id}")
    DraftResponse patch(@PathVariable UUID id, @RequestBody @Valid PatchRequest body) {
        return documents.toResponse(documents.patch(CurrentUser.require().id(), id, body));
    }

    /**
     * Approves the draft: its content becomes final and immutable. 409 {@code fact_check_failed} while a blocking
     * flag remains (the fact check is run again on the final content), 409 {@code already_approved}.
     */
    @PostMapping("/documents/{id}/approve")
    DraftResponse approve(@PathVariable UUID id) {
        return documents.toResponse(documents.approve(CurrentUser.require().id(), id));
    }

    /** Deletes a draft. An approved document cannot be deleted (409 {@code document_approved}). */
    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        documents.delete(CurrentUser.require().id(), id);
    }
}
