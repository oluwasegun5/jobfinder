package com.jobfinder.core.admin.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.ingestion.InvalidSourceTargetException;
import com.jobfinder.core.ingestion.SourceTargetService;
import com.jobfinder.core.ingestion.SourceTargetView;
import com.jobfinder.core.ingestion.UnknownSourceException;
import com.jobfinder.core.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin endpoints for job-source ingestion. Everything under {@code /admin} requires the ADMIN role (see the
 * security configuration); a signed-in non-admin gets 403. These endpoints touch no user data.
 */
@RestController
@RequestMapping("/admin/ingestion")
class AdminIngestionController {

    /** {@code source} is the code of a registered source (GREENHOUSE), {@code identifier} what it fetches (a board token). */
    record AddTargetRequest(@NotBlank @Size(max = 40) String source, @NotBlank @Size(max = 255) String identifier,
            @NotBlank @Size(max = 300) String companyName) {
    }

    /** {@code created} is false when the target already existed and was left as it was. */
    record TargetResponse(UUID id, String source, String identifier, String companyName, boolean enabled,
            boolean created) {

        static TargetResponse of(SourceTargetView view) {
            return new TargetResponse(view.id(), view.sourceCode(), view.identifier(), view.companyName(),
                    view.enabled(), view.created());
        }
    }

    private final SourceTargetService targets;

    AdminIngestionController(SourceTargetService targets) {
        this.targets = targets;
    }

    /** Adds a target to a source: 201 when new, 200 when it was already there (nothing changes). */
    @PostMapping("/targets")
    ResponseEntity<TargetResponse> addTarget(@Valid @RequestBody AddTargetRequest request) {
        try {
            SourceTargetView view = targets.addTarget(request.source(), request.identifier(), request.companyName());
            return ResponseEntity.status(view.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(TargetResponse.of(view));
        } catch (UnknownSourceException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "unknown_source", "No such job source.");
        } catch (InvalidSourceTargetException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_target", e.getMessage());
        }
    }
}
