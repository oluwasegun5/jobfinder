package com.jobfinder.core.documents.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jobfinder.core.billing.AiAllowanceException;
import com.jobfinder.core.billing.AiDailyCapReachedException;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentStatus;
import com.jobfinder.core.documents.internal.DocumentDtos.DocumentType;
import com.jobfinder.core.documents.internal.DocumentDtos.JobRef;
import com.jobfinder.core.documents.internal.DocumentStore.Row;
import com.jobfinder.core.documents.internal.PackStore.PackRow;
import com.jobfinder.core.documents.internal.PackStore.PartRecord;
import com.jobfinder.core.documents.internal.WritingDtos.PackList;
import com.jobfinder.core.documents.internal.WritingDtos.PackOptions;
import com.jobfinder.core.documents.internal.WritingDtos.PackRequest;
import com.jobfinder.core.documents.internal.WritingDtos.PackResponse;
import com.jobfinder.core.documents.internal.WritingDtos.PackStatus;
import com.jobfinder.core.documents.internal.WritingDtos.PackSummary;
import com.jobfinder.core.documents.internal.WritingDtos.PartError;
import com.jobfinder.core.documents.internal.WritingDtos.PartState;
import com.jobfinder.core.documents.internal.WritingDtos.PartView;
import com.jobfinder.core.documents.internal.WritingDtos.RetryRequest;
import com.jobfinder.core.documents.internal.WritingDtos.WritingRequest;
import com.jobfinder.core.jobs.JobForMatching;
import com.jobfinder.core.jobs.JobMatchSource;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidateProfiles;
import com.jobfinder.core.shared.ApiException;

/**
 * The application pack of one job (docs/adr/0031-cover-letters-and-application-pack.md): the tailored CV, the cover
 * letter and the screening answers, made in that order, each as the document it is anywhere else (reviewed, edited,
 * approved and rendered one by one).
 *
 * <p>The pack is a plan and a record, not a transaction: a part that fails, or finds the daily AI allowance used up,
 * is recorded as FAILED or BLOCKED_BY_CAP with a typed error and the other parts are kept; {@link #retry} makes only
 * the parts that need it. A CV that already exists for the job and resume version (approved first, else the newest
 * draft) is reused instead of tailored again. One pack per user, job and resume version (a unique index) and a
 * conditional start of every run make a double click one run.
 */
@Service
class PackService {

    /** A pack and whether this call created it. */
    record Result(PackRow pack, boolean created) {
    }

    private static final Logger log = LoggerFactory.getLogger(PackService.class);
    private static final List<DocumentType> ORDER = List.of(DocumentType.TAILORED_RESUME, DocumentType.COVER_LETTER,
            DocumentType.SCREENING_ANSWERS);

    private final PackStore packs;
    private final DocumentStore store;
    private final DocumentService documents;
    private final WritingService writing;
    private final CandidateProfiles candidates;
    private final JobMatchSource jobs;
    private final DocumentsProperties properties;
    private final Clock clock;

    PackService(PackStore packs, DocumentStore store, DocumentService documents, WritingService writing,
            CandidateProfiles candidates, JobMatchSource jobs, DocumentsProperties properties, Clock clock) {
        this.packs = packs;
        this.store = store;
        this.documents = documents;
        this.writing = writing;
        this.candidates = candidates;
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    // --- create ---

    /**
     * Makes the pack for the job, or returns the one that exists: the same options return it untouched (nothing is
     * generated, nothing is spent); other options make the letter and the answers again with them (the old drafts are
     * kept as history, approved ones are never touched) and reuse the CV.
     */
    Result create(UUID userId, UUID jobId, PackRequest request) {
        Candidate candidate = candidates.candidate(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "resume_required", "Upload and parse a resume before making an application pack."));
        JobForMatching job = jobs.jobs(List.of(jobId)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "Job not found."));
        UUID versionId = candidate.resumeVersionId();
        PackOptions options = new PackOptions(request.tone(), request.length(), request.notes(),
                ordered(request.parts()));

        Map<String, PartRecord> parts = new LinkedHashMap<>();
        options.include().forEach(t -> parts.put(t.name(), PartRecord.pending()));
        UUID id = UUID.randomUUID();
        try {
            packs.insert(id, userId, jobId, clip(job.title(), 400), clip(job.company(), 400), versionId, options,
                    parts);
        } catch (DuplicateKeyException e) {
            PackRow existing = packs.findByJob(userId, jobId, versionId).orElseThrow(() -> new ApiException(
                    HttpStatus.CONFLICT, "pack_in_progress", "This pack is being made. Try again in a moment."));
            if (sameOptions(existing.options(), options)) {
                return new Result(existing, false);
            }
            Map<String, PartRecord> next = new LinkedHashMap<>();
            for (DocumentType type : options.include()) {
                PartRecord old = existing.parts().get(type.name());
                // The CV does not depend on the writing options: what is there is kept; the prose is made again.
                next.put(type.name(), type == DocumentType.TAILORED_RESUME && old != null
                        && old.state() == PartState.READY ? old : PartRecord.pending());
            }
            if (!packs.beginRun(existing.id(), userId, options, next, cutoff())) {
                return new Result(packs.find(userId, existing.id()).orElse(existing), false);
            }
            run(userId, existing.id(), jobId, versionId, options, next, options.include());
            return new Result(packs.find(userId, existing.id()).orElseThrow(), false);
        }
        run(userId, id, jobId, versionId, options, parts, options.include());
        return new Result(packs.find(userId, id).orElseThrow(), true);
    }

    /** Makes the parts that need it again (see {@link RetryRequest}); a pack with nothing to retry is returned as is. */
    PackRow retry(UUID userId, UUID id, RetryRequest request) {
        PackRow pack = get(userId, id);
        List<DocumentType> requested = request == null || request.parts() == null || request.parts().isEmpty()
                ? pack.options().include() : ordered(request.parts());
        for (DocumentType type : requested) {
            if (!pack.options().include().contains(type)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_part",
                        "The pack has no " + type.name() + " part.");
            }
        }
        boolean abandoned = pack.status() == PackStatus.GENERATING && pack.updatedAt().isBefore(cutoff());
        List<DocumentType> eligible = new ArrayList<>();
        for (DocumentType type : requested) {
            PartState state = resolve(userId, pack, type).state();
            if (state == PartState.FAILED || state == PartState.BLOCKED_BY_CAP || state == PartState.MISSING
                    || (state == PartState.PENDING && abandoned)) {
                eligible.add(type);
            }
        }
        if (eligible.isEmpty()) {
            return pack;
        }
        Map<String, PartRecord> next = new LinkedHashMap<>(pack.parts());
        eligible.forEach(t -> next.put(t.name(), PartRecord.pending()));
        if (!packs.beginRun(id, userId, pack.options(), next, cutoff())) {
            return packs.find(userId, id).orElse(pack);
        }
        run(userId, id, pack.jobId(), pack.baseResumeVersionId(), pack.options(), next, eligible);
        return packs.find(userId, id).orElseThrow();
    }

    private void run(UUID userId, UUID id, UUID jobId, UUID versionId, PackOptions options,
            Map<String, PartRecord> parts, List<DocumentType> toRun) {
        WritingRequest writingRequest = new WritingRequest(options.tone(), options.length(), options.notes());
        for (DocumentType type : toRun) {
            parts.put(type.name(), part(userId, jobId, versionId, type, writingRequest));
            packs.saveParts(id, PackStatus.GENERATING, parts);
        }
        packs.saveParts(id, summary(options, parts), parts);
    }

    /** Makes one part; a failure becomes the part's typed error, never an exception (the other parts go on). */
    private PartRecord part(UUID userId, UUID jobId, UUID versionId, DocumentType type, WritingRequest request) {
        try {
            Row row;
            if (type == DocumentType.TAILORED_RESUME) {
                Optional<Row> reusable = store.findReusable(userId, jobId, versionId, type);
                row = reusable.isPresent() ? reusable.get() : documents.tailor(userId, jobId, null).row();
            } else {
                row = writing.generate(userId, jobId, type, request).row();
            }
            if (row.status() == DocumentStatus.GENERATING) {
                return failed("generation_in_progress", "This document is being made by another request. Retry in a"
                        + " moment.", true);
            }
            return PartRecord.ready(row.id());
        } catch (AiAllowanceException e) {
            // The daily cap (with its reset time) or a spent credit balance (restored by a grant or top-up).
            Instant resetsAt = e instanceof AiDailyCapReachedException cap ? cap.resetsAt() : null;
            return new PartRecord(PartState.BLOCKED_BY_CAP, null,
                    new PartError(e.code(), e.getMessage(), true, resetsAt));
        } catch (ApiException e) {
            return failed(e.code(), e.getMessage(), e.status().is5xxServerError());
        } catch (RuntimeException e) {
            log.error("Pack part {} failed", type, e);
            return failed("generation_failed", "This part could not be made. Try again in a moment.", true);
        }
    }

    private static PartRecord failed(String code, String message, boolean retryable) {
        return new PartRecord(PartState.FAILED, null, new PartError(code, message, retryable, null));
    }

    private static PackStatus summary(PackOptions options, Map<String, PartRecord> parts) {
        long ready = options.include().stream()
                .filter(t -> parts.get(t.name()) != null && parts.get(t.name()).state() == PartState.READY).count();
        if (ready == options.include().size()) {
            return PackStatus.COMPLETE;
        }
        return ready == 0 ? PackStatus.FAILED : PackStatus.PARTIAL;
    }

    private Instant cutoff() {
        return Instant.now(clock).minus(properties.tailoring().generationTimeout());
    }

    private static boolean sameOptions(PackOptions a, PackOptions b) {
        return a.tone() == b.tone() && a.length() == b.length() && Objects.equals(a.notes(), b.notes())
                && a.include().equals(b.include());
    }

    private static List<DocumentType> ordered(List<DocumentType> types) {
        return ORDER.stream().filter(types::contains).toList();
    }

    // --- read ---

    PackRow get(UUID userId, UUID id) {
        return packs.find(userId, id).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "pack_not_found", "Application pack not found."));
    }

    List<PackRow> list(UUID userId, UUID jobId, Integer limit) {
        return packs.list(userId, jobId, limit == null ? 20 : limit);
    }

    /** What a part is now: its record, but a READY one whose document was deleted is MISSING. */
    private record Resolved(PartState state, Row row, PartError error) {
    }

    private Resolved resolve(UUID userId, PackRow pack, DocumentType type) {
        PartRecord rec = pack.parts().get(type.name());
        if (rec == null || rec.state() == PartState.PENDING) {
            return new Resolved(PartState.PENDING, null, null);
        }
        if (rec.state() != PartState.READY) {
            return new Resolved(rec.state(), null, rec.error());
        }
        Row row = store.find(userId, rec.documentId(), false).orElse(null);
        if (row == null || row.status() == DocumentStatus.SUPERSEDED) {
            // A letter regenerated on its own replaced this draft: the pack follows to the current document.
            row = store.findReusable(userId, pack.jobId(), pack.baseResumeVersionId(), type).orElse(null);
        }
        if (row == null) {
            return new Resolved(PartState.MISSING, null, new PartError("document_missing",
                    "This draft was deleted. Retry makes a new one.", true, null));
        }
        return new Resolved(PartState.READY, row, null);
    }

    PackResponse toResponse(UUID userId, PackRow pack) {
        List<PartView> views = new ArrayList<>();
        boolean missing = false;
        for (DocumentType type : pack.options().include()) {
            Resolved r = resolve(userId, pack, type);
            missing |= r.state() == PartState.MISSING;
            views.add(new PartView(type, r.state(), r.row() == null ? null : documents.toResponse(r.row()),
                    r.error()));
        }
        PackStatus status = missing && pack.status() == PackStatus.COMPLETE ? PackStatus.PARTIAL : pack.status();
        return new PackResponse(pack.id(), status, new JobRef(pack.jobId(), pack.jobTitle(), pack.jobCompany()),
                pack.baseResumeVersionId(), pack.options(), views, pack.version(), pack.createdAt(),
                pack.updatedAt());
    }

    PackList toList(List<PackRow> rows) {
        return new PackList(rows.stream().map(p -> new PackSummary(p.id(), p.status(),
                new JobRef(p.jobId(), p.jobTitle(), p.jobCompany()), p.version(), p.createdAt(), p.updatedAt()))
                .toList());
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
