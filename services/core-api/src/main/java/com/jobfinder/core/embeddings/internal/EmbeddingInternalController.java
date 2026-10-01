package com.jobfinder.core.embeddings.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.embeddings.internal.EmbeddingDtos.BackfillResponse;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.InputsRequest;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.InputsResponse;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.ResultsRequest;
import com.jobfinder.core.embeddings.internal.EmbeddingDtos.ResultsResponse;

import jakarta.validation.Valid;

/**
 * Service-to-service endpoints for the embeddings pipeline. They are authenticated with the shared
 * {@code X-Service-Token} (see {@link InternalSecurityConfig}), never with a user's token, and are left out of the
 * public OpenAPI document (springdoc {@code paths-to-exclude}), so they are not in the generated web client.
 * Keep {@code /internal/**} blocked at the public edge.
 */
@RestController
@RequestMapping("/internal/v1/embeddings")
class EmbeddingInternalController {

    enum Scope {
        ALL, JOBS, RESUMES
    }

    private final EmbeddingService service;
    private final EmbeddingBackfill backfill;

    EmbeddingInternalController(EmbeddingService service, EmbeddingBackfill backfill) {
        this.service = service;
        this.backfill = backfill;
    }

    /** The text to embed for each id that needs a (new) embedding, and why the others do not. */
    @PostMapping("/inputs")
    InputsResponse inputs(@Valid @RequestBody InputsRequest request) {
        return service.inputs(request.kind(), request.ids());
    }

    /** Stores vectors computed from the texts of {@code /inputs}. */
    @PutMapping("/results")
    ResultsResponse results(@Valid @RequestBody ResultsRequest request) {
        return service.store(request);
    }

    /** Queues every job and resume version with a missing or stale embedding (ADR 0022). */
    @PostMapping("/backfill")
    BackfillResponse backfill(@RequestParam(defaultValue = "ALL") Scope scope) {
        return backfill.run(scope != Scope.RESUMES, scope != Scope.JOBS);
    }
}
