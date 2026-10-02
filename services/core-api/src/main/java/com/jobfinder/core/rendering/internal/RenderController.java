package com.jobfinder.core.rendering.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.rendering.internal.RenderDtos.RenderRequest;
import com.jobfinder.core.rendering.internal.RenderDtos.RenderedFileResponse;
import com.jobfinder.core.rendering.internal.RenderDtos.RenderedFileSummary;
import com.jobfinder.core.rendering.internal.RenderService.Result;

import jakarta.validation.Valid;

/**
 * Resume export (docs/adr/0030-document-rendering.md). Both endpoints answer with the file's metadata and a
 * short-lived pre-signed download URL: 201 when the file was just rendered, 200 when it already existed (nothing is
 * rendered again). Someone else's document or resume is a 404, never a 403.
 */
@RestController
class RenderController {

    private final RenderService service;

    RenderController(RenderService service) {
        this.service = service;
    }

    /**
     * Renders an approved tailored resume. 409 {@code document_not_approved} for a draft, 404 for an unknown or
     * someone else's document.
     */
    @PostMapping("/documents/{id}/render")
    ResponseEntity<RenderedFileResponse> renderDocument(@PathVariable UUID id,
            @RequestBody(required = false) @Valid RenderRequest body) {
        return respond(service.renderDocument(CurrentUser.require().id(), id, orDefaults(body)));
    }

    /**
     * Renders the latest content of one of the caller's own resumes (no tailoring needed). 409
     * {@code resume_content_required} while the resume has no content yet.
     */
    @PostMapping("/resumes/{id}/render")
    ResponseEntity<RenderedFileResponse> renderResume(@PathVariable UUID id,
            @RequestBody(required = false) @Valid RenderRequest body) {
        return respond(service.renderResume(CurrentUser.require().id(), id, orDefaults(body)));
    }

    /** The files already rendered from an approved document, without links. */
    @GetMapping("/documents/{id}/files")
    List<RenderedFileSummary> listFiles(@PathVariable UUID id) {
        return service.listDocumentFiles(CurrentUser.require().id(), id);
    }

    /** A fresh short-lived pre-signed download link for one of those files; renders nothing. */
    @GetMapping("/documents/{id}/files/{fileId}/download")
    RenderedFileResponse download(@PathVariable UUID id, @PathVariable UUID fileId) {
        return service.downloadDocumentFile(CurrentUser.require().id(), id, fileId);
    }

    private static RenderRequest orDefaults(RenderRequest body) {
        return body == null ? RenderRequest.defaults() : body;
    }

    private static ResponseEntity<RenderedFileResponse> respond(Result result) {
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.file());
    }
}
