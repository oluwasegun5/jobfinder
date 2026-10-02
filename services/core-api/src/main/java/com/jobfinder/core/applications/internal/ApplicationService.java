package com.jobfinder.core.applications.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationDetail;
import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationStatus;
import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationView;
import com.jobfinder.core.applications.internal.ApplicationDtos.CancelReason;
import com.jobfinder.core.applications.internal.ApplicationDtos.CreateRequest;
import com.jobfinder.core.applications.internal.ApplicationDtos.EventView;
import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationListResponse;
import com.jobfinder.core.applications.internal.ApplicationDtos.StatusRequest;
import com.jobfinder.core.applications.internal.ApplicationDtos.UpdateRequest;
import com.jobfinder.core.applications.internal.ApplicationStore.Row;
import com.jobfinder.core.documents.ApplicationPacks;
import com.jobfinder.core.documents.ApprovedDocument;
import com.jobfinder.core.documents.ApprovedDocuments;
import com.jobfinder.core.jobs.JobForMatching;
import com.jobfinder.core.jobs.JobMatchSource;
import com.jobfinder.core.shared.ApiException;

/**
 * The application tracker (docs/adr/0032-application-tracker.md): create (from a job or by hand), read, change, move
 * between the columns of the board, delete. Every method takes the caller's user id and finds rows only through it.
 *
 * <p>One application per user and job: creating it again returns the one that exists (not created), whatever the request
 * says, which is what makes a double click on "I applied" safe; to move it, change its status. Every status change is an
 * append-only event, and a change to the status the application already has is a no-op that records nothing.
 */
@Service
class ApplicationService {

    /** An application and whether this call created it. */
    record Outcome(Row row, boolean created) {
    }

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 500;
    private static final int TITLE_MAX = 400;

    private final ApplicationStore store;
    private final ReminderStore reminders;
    private final JobMatchSource jobs;
    private final ApprovedDocuments documents;
    private final ApplicationPacks packs;
    private final ApplicationsProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    ApplicationService(ApplicationStore store, ReminderStore reminders, JobMatchSource jobs,
            ApprovedDocuments documents, ApplicationPacks packs, ApplicationsProperties properties,
            TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.reminders = reminders;
        this.jobs = jobs;
        this.documents = documents;
        this.packs = packs;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    // --- create ---

    Outcome create(UUID userId, CreateRequest request) {
        if (request.jobId() != null) {
            Optional<Row> existing = store.findByJob(userId, request.jobId());
            if (existing.isPresent()) {
                return new Outcome(existing.get(), false);
            }
        }
        Instant now = Instant.now(clock);
        ApplicationStatus status = request.status() == null ? ApplicationStatus.APPLIED : request.status();
        Instant appliedAt = appliedAt(status, request.appliedAt(), now);

        String title;
        String company;
        if (request.jobId() != null) {
            JobForMatching job = jobs.jobs(List.of(request.jobId())).stream().findFirst().orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "Job not found."));
            title = clip(job.title(), TITLE_MAX);
            company = blankToNull(clip(job.company(), TITLE_MAX));
        } else {
            title = request.title() == null ? "" : request.title().strip();
            if (title.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "title_required",
                        "An application entered by hand needs a title.");
            }
            company = blankToNull(request.company());
        }
        String url = url(request.url());
        validateLinks(userId, request);
        if (store.countAll(userId) >= properties.maxPerUser()) {
            throw new ApiException(HttpStatus.CONFLICT, "application_limit_reached",
                    "You can keep up to " + properties.maxPerUser() + " applications. Delete some to add more.");
        }

        Row row = new Row(UUID.randomUUID(), userId, request.jobId(), title, company, url, status,
                blankToNull(request.notes()), appliedAt, request.packId(), request.resumeDocumentId(),
                request.coverLetterDocumentId(), request.screeningAnswersDocumentId(), now, null, now, now);
        try {
            tx.executeWithoutResult(s -> {
                store.insert(row);
                store.insertEvent(UUID.randomUUID(), row.id(), userId, null, status, null, now);
            });
        } catch (DuplicateKeyException e) {
            // Another request for the same job got there first: hand back what is there.
            Row existing = request.jobId() == null ? null : store.findByJob(userId, request.jobId()).orElse(null);
            if (existing == null) {
                throw e;
            }
            return new Outcome(existing, false);
        }
        return new Outcome(store.find(userId, row.id()).orElseThrow(), true);
    }

    private Instant appliedAt(ApplicationStatus status, Instant given, Instant now) {
        if (status == ApplicationStatus.SAVED) {
            if (given != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_applied_at",
                        "appliedAt cannot be set on an application that is only saved.");
            }
            return null;
        }
        if (given != null) {
            requireNotFuture(given, now);
            return given;
        }
        return status.applied() ? now : null;
    }

    private static void requireNotFuture(Instant at, Instant now) {
        if (at.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_applied_at", "appliedAt cannot be in the future.");
        }
    }

    /** The pack and the documents named must be the caller's, approved, of the right type and for the same job. */
    private void validateLinks(UUID userId, CreateRequest r) {
        if (r.packId() != null) {
            UUID packJob = packs.jobOf(userId, r.packId()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                    "invalid_pack", "packId is not an application pack of yours."));
            if (r.jobId() != null && !r.jobId().equals(packJob)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "document_job_mismatch",
                        "packId was made for another job.");
            }
        }
        link(userId, r.jobId(), "resumeDocumentId", "TAILORED_RESUME", r.resumeDocumentId());
        link(userId, r.jobId(), "coverLetterDocumentId", "COVER_LETTER", r.coverLetterDocumentId());
        link(userId, r.jobId(), "screeningAnswersDocumentId", "SCREENING_ANSWERS", r.screeningAnswersDocumentId());
    }

    private void link(UUID userId, UUID jobId, String field, String type, UUID documentId) {
        if (documentId == null) {
            return;
        }
        ApprovedDocument doc = documents.approved(userId, documentId).filter(d -> d.type().equals(type))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_document",
                        field + " is not an approved " + type + " of yours."));
        if (jobId != null && !jobId.equals(doc.jobId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "document_job_mismatch",
                    field + " was made for another job.");
        }
    }

    // --- read ---

    Row get(UUID userId, UUID id) {
        return store.find(userId, id).orElseThrow(ApplicationService::notFound);
    }

    ApplicationDetail detail(UUID userId, Row row) {
        List<EventView> events = store.events(userId, row.id()).stream()
                .map(e -> new EventView(e.id(), e.from(), e.to(), e.note(), e.at())).toList();
        return ApplicationDetail.of(view(row), events, reminders.list(userId, row.id()).stream()
                .map(ReminderService::view).toList());
    }

    ApplicationListResponse list(UUID userId, List<ApplicationStatus> statuses, boolean grouped, Integer limit) {
        int max = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<ApplicationStatus> filter = statuses == null ? List.of() : statuses.stream().distinct().toList();
        List<Row> rows = store.list(userId, filter, max + 1);
        boolean truncated = rows.size() > max;
        List<ApplicationView> views = rows.stream().limit(max).map(ApplicationService::view).toList();
        Map<ApplicationStatus, Integer> counts = store.counts(userId);
        if (!grouped) {
            return new ApplicationListResponse(views, null, counts, truncated);
        }
        Map<ApplicationStatus, List<ApplicationView>> board = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus s : ApplicationStatus.values()) {
            if (filter.isEmpty() || filter.contains(s)) {
                board.put(s, List.of());
            }
        }
        board.putAll(views.stream().collect(Collectors.groupingBy(ApplicationView::status,
                () -> new EnumMap<>(ApplicationStatus.class), Collectors.toList())));
        return new ApplicationListResponse(null, board, counts, truncated);
    }

    // --- change ---

    Row update(UUID userId, UUID id, UpdateRequest request) {
        Instant now = Instant.now(clock);
        tx.executeWithoutResult(s -> {
            Row row = store.findForUpdate(userId, id).orElseThrow(ApplicationService::notFound);
            String title = row.title();
            String company = row.company();
            if (request.title() != null || request.company() != null) {
                if (row.jobId() != null && ((request.title() != null && !request.title().strip().equals(row.title()))
                        || (request.company() != null && !Objects.equals(blankToNull(request.company()), row.company())))) {
                    throw new ApiException(HttpStatus.CONFLICT, "job_fields_fixed",
                            "The title and company of an application made from a job come from the job.");
                }
                if (request.title() != null) {
                    title = request.title().strip();
                    if (title.isEmpty()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "title_required", "The title cannot be empty.");
                    }
                }
                if (request.company() != null) {
                    company = blankToNull(request.company());
                }
            }
            String url = request.url() == null ? row.url() : url(request.url());
            String notes = request.notes() == null ? row.notes() : blankToNull(request.notes());
            Instant appliedAt = row.appliedAt();
            if (request.appliedAt() != null) {
                if (row.status() == ApplicationStatus.SAVED) {
                    throw new ApiException(HttpStatus.CONFLICT, "not_applied_yet",
                            "appliedAt can be set once the application is no longer only saved.");
                }
                requireNotFuture(request.appliedAt(), now);
                appliedAt = request.appliedAt();
            }
            store.update(id, title, company, url, notes, appliedAt, now);
        });
        return store.find(userId, id).orElseThrow(ApplicationService::notFound);
    }

    /**
     * Moves the application to {@code request.status()} and records the event. The same status is a no-op (nothing is
     * recorded). Any move between the other statuses is allowed, backwards too (a card dragged back, a rejection that was
     * a mistake), except back to SAVED once it has left it (409 {@code invalid_transition}): an application that was
     * applied for stays applied for. The first move out of SAVED into a status that means "applied" sets
     * {@code appliedAt}; REJECTED and WITHDRAWN cancel the application's pending reminders.
     */
    Row changeStatus(UUID userId, UUID id, StatusRequest request) {
        Instant now = Instant.now(clock);
        tx.executeWithoutResult(s -> {
            Row row = store.findForUpdate(userId, id).orElseThrow(ApplicationService::notFound);
            ApplicationStatus to = request.status();
            if (to == row.status()) {
                return;
            }
            if (to == ApplicationStatus.SAVED) {
                throw new ApiException(HttpStatus.CONFLICT, "invalid_transition",
                        "An application that has left SAVED cannot go back to it.");
            }
            Instant appliedAt = row.appliedAt();
            if (appliedAt == null && row.status() == ApplicationStatus.SAVED && to.applied()) {
                appliedAt = now;
            }
            store.changeStatus(id, to, appliedAt, now);
            store.insertEvent(UUID.randomUUID(), id, userId, row.status(), to, blankToNull(request.note()), now);
            if (to.closed()) {
                reminders.cancelPending(id, CancelReason.APPLICATION_CLOSED, now);
            }
        });
        return store.find(userId, id).orElseThrow(ApplicationService::notFound);
    }

    void delete(UUID userId, UUID id) {
        if (!store.delete(userId, id)) {
            throw notFound();
        }
    }

    // --- plumbing ---

    static ApplicationView view(Row r) {
        return new ApplicationView(r.id(), r.jobId(), r.title(), r.company(), r.url(), r.status(), r.notes(),
                r.appliedAt(), r.packId(), r.resumeDocumentId(), r.coverLetterDocumentId(),
                r.screeningAnswersDocumentId(), r.statusChangedAt(), r.nextReminderAt(), r.createdAt(),
                r.updatedAt());
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "application_not_found", "Application not found.");
    }

    /** An http(s) URL with a host, at most 2000 characters, or null for none. */
    static String url(String value) {
        String url = blankToNull(value);
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (url.length() > 2000 || scheme == null || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
                throw new URISyntaxException(url, "not an http(s) URL");
            }
        } catch (URISyntaxException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_url",
                    "url must be an http or https address of at most 2000 characters.");
        }
        return url;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
