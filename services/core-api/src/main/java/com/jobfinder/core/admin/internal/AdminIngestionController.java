package com.jobfinder.core.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.ingestion.IngestionRunPage;
import com.jobfinder.core.ingestion.IngestionRunStatus;
import com.jobfinder.core.ingestion.IngestionRunView;
import com.jobfinder.core.ingestion.InvalidSourceTargetException;
import com.jobfinder.core.ingestion.SourceAdminService;
import com.jobfinder.core.ingestion.SourceAlertView;
import com.jobfinder.core.ingestion.SourceKind;
import com.jobfinder.core.ingestion.SourceOverview;
import com.jobfinder.core.ingestion.SourceSchedule;
import com.jobfinder.core.ingestion.SourceTargetService;
import com.jobfinder.core.ingestion.SourceTargetView;
import com.jobfinder.core.ingestion.SourceUnavailableException;
import com.jobfinder.core.ingestion.UnknownSourceException;
import com.jobfinder.core.shared.ApiException;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin endpoints for job-source ingestion: the source dashboard (ADR 0024) and the targets a source fetches.
 * Everything under {@code /admin} requires the ADMIN role (see the security configuration); a signed-in non-admin
 * gets 403. These endpoints touch no user data.
 */
@RestController
@RequestMapping("/admin/ingestion")
class AdminIngestionController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * {@code source} is the code of a registered source (GREENHOUSE), {@code identifier} what it fetches (a board
     * token, or for an aggregator the search, e.g. {@code gb:software engineer}). {@code companyName} is required for
     * an ATS board and must be left out for an aggregator search, whose postings name their own employers.
     */
    record AddTargetRequest(@NotBlank @Size(max = 40) String source, @NotBlank @Size(max = 255) String identifier,
            @Size(max = 300) String companyName) {
    }

    /** {@code created} is false when the target already existed and was left as it was. */
    record TargetResponse(UUID id, String source, String identifier, String companyName, boolean enabled,
            boolean created) {

        static TargetResponse of(SourceTargetView view) {
            return new TargetResponse(view.id(), view.sourceCode(), view.identifier(), view.companyName(),
                    view.enabled(), view.created());
        }
    }

    /** One run of a source, with its counts. */
    record IngestionRunResponse(UUID id, String source, IngestionRunStatus status, Instant startedAt, Instant finishedAt,
            int targets, int fetched, int created, int updated, int expired, int errors, String errorSummary) {

        static IngestionRunResponse of(IngestionRunView run) {
            return new IngestionRunResponse(run.id(), run.sourceCode(), run.status(), run.startedAt(), run.finishedAt(),
                    run.targets(), run.fetched(), run.created(), run.updated(), run.expired(), run.errors(),
                    run.errorSummary());
        }
    }

    /** An alert that is open on a source: {@code rule} is ZERO_JOBS or ERROR_RATE. */
    record SourceAlertResponse(String rule, Instant since, Instant lastNotifiedAt, int occurrences, String detail) {

        static SourceAlertResponse of(SourceAlertView alert) {
            return new SourceAlertResponse(alert.rule(), alert.since(), alert.lastNotifiedAt(), alert.occurrences(),
                    alert.detail());
        }
    }

    /**
     * A source on the dashboard. {@code health} is UNKNOWN, HEALTHY, DEGRADED or FAILING. {@code schedule} says
     * whether the scheduler will pick it up; {@code unavailableReason} is set when it cannot run (a missing API key).
     * {@code lastRun} is the latest run that has finished; {@code nextDueAt} is null when the source is not
     * scheduled or has never run (due at once).
     */
    record IngestionSourceResponse(String code, SourceKind kind, boolean enabled, SourceSchedule schedule,
            String unavailableReason, String health, Instant lastRunAt, Instant nextDueAt, boolean running,
            int enabledTargets, int totalTargets, IngestionRunResponse lastRun, List<SourceAlertResponse> alerts) {

        static IngestionSourceResponse of(SourceOverview source) {
            return new IngestionSourceResponse(source.code(), source.kind(), source.enabled(), source.schedule(),
                    source.unavailableReason(), source.health(), source.lastRunAt(), source.nextDueAt(),
                    source.running(), source.enabledTargets(), source.totalTargets(),
                    source.lastRun() == null ? null : IngestionRunResponse.of(source.lastRun()),
                    source.alerts().stream().map(SourceAlertResponse::of).toList());
        }
    }

    record IngestionSourceListResponse(List<IngestionSourceResponse> items) {
    }

    record SetSourceEnabledRequest(@NotNull Boolean enabled) {
    }

    /** The run was started in the background; poll the source list or the run history for its outcome. */
    record IngestionRunStartedResponse(String source, String status) {
    }

    record IngestionRunPageResponse(List<IngestionRunResponse> items, int page, int size, long totalElements, int totalPages) {

        static IngestionRunPageResponse of(IngestionRunPage page) {
            return new IngestionRunPageResponse(page.items().stream().map(IngestionRunResponse::of).toList(), page.page(), page.size(),
                    page.totalElements(), page.totalPages());
        }
    }

    private final SourceTargetService targets;
    private final SourceAdminService sources;

    AdminIngestionController(SourceTargetService targets, SourceAdminService sources) {
        this.targets = targets;
        this.sources = sources;
    }

    /** Every registered source with its health, last run, open alerts and what the scheduler will do with it. */
    @GetMapping("/sources")
    IngestionSourceListResponse listSources() {
        return new IngestionSourceListResponse(sources.sources().stream().map(IngestionSourceResponse::of).toList());
    }

    /**
     * Switches a source on or off for the scheduler. Takes effect at the next tick; a run already in progress is not
     * interrupted, and a manual run still works while the source is off.
     */
    @PutMapping("/sources/{code}/enabled")
    IngestionSourceResponse setEnabled(@PathVariable String code, @Valid @RequestBody SetSourceEnabledRequest request) {
        try {
            return IngestionSourceResponse.of(sources.setEnabled(code, request.enabled()));
        } catch (UnknownSourceException e) {
            throw unknownSource();
        }
    }

    /** Starts a run of the source in the background: 202 at once, 409 if one is already running. */
    @PostMapping("/sources/{code}/runs")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "The run was started",
                    content = @Content(schema = @Schema(implementation = IngestionRunStartedResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "A run of this source is already in progress (code run_in_progress), or the source "
                            + "cannot run (code source_unavailable)") })
    ResponseEntity<IngestionRunStartedResponse> startRun(@PathVariable String code) {
        try {
            return switch (sources.startRun(code)) {
                case STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new IngestionRunStartedResponse(code, "STARTED"));
                case ALREADY_RUNNING -> throw new ApiException(HttpStatus.CONFLICT, "run_in_progress",
                        "A run of this source is already in progress.");
            };
        } catch (UnknownSourceException e) {
            throw unknownSource();
        } catch (SourceUnavailableException e) {
            throw new ApiException(HttpStatus.CONFLICT, "source_unavailable", e.getMessage());
        }
    }

    /** The run history, newest first, optionally for one source. */
    @GetMapping("/runs")
    IngestionRunPageResponse listRuns(@RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        if (page < 0 || size < 1 || size > SourceAdminService.MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_page",
                    "page must be at least 0 and size between 1 and " + SourceAdminService.MAX_PAGE_SIZE + ".");
        }
        try {
            return IngestionRunPageResponse.of(sources.runs(source == null || source.isBlank() ? null : source, page, size));
        } catch (UnknownSourceException e) {
            throw unknownSource();
        }
    }

    /** Adds a target to a source: 201 when new, 200 when it was already there (nothing changes). */
    @PostMapping("/targets")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The target was created",
                    content = @Content(schema = @Schema(implementation = TargetResponse.class))),
            @ApiResponse(responseCode = "200", description = "The target already existed and was left as it was",
                    content = @Content(schema = @Schema(implementation = TargetResponse.class))) })
    ResponseEntity<TargetResponse> addTarget(@Valid @RequestBody AddTargetRequest request) {
        try {
            SourceTargetView view = targets.addTarget(request.source(), request.identifier(), request.companyName());
            return ResponseEntity.status(view.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(TargetResponse.of(view));
        } catch (UnknownSourceException e) {
            throw unknownSource();
        } catch (InvalidSourceTargetException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_target", e.getMessage());
        }
    }

    private static ApiException unknownSource() {
        return new ApiException(HttpStatus.NOT_FOUND, "unknown_source", "No such job source.");
    }
}
