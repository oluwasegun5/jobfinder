package com.jobfinder.core.profile.internal;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.profile.internal.ResumeDtos.DownloadUrlResponse;
import com.jobfinder.core.profile.internal.ResumeDtos.ResumeResponse;
import com.jobfinder.core.shared.ApiException;
import com.jobfinder.core.storage.ObjectStorage;

/**
 * CV upload (which queues parsing), listing, download links, primary selection and deletion. Every method takes the
 * caller's user ID and scopes by it: someone else's resume is reported as not found.
 *
 * <p>Deliberately not {@code @Transactional} as a whole: object storage is not part of the
 * database transaction, so each method orders its storage and database writes explicitly.
 */
@Service
class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final int MAX_LABEL_LENGTH = 120;

    private final ResumeRepository resumes;
    private final ResumeVersionRepository versions;
    private final ObjectStorage storage;
    private final ResumeProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final ResumeParseStore parseStore;
    private final AiUsageGate gate;

    ResumeService(ResumeRepository resumes, ResumeVersionRepository versions, ObjectStorage storage,
            ResumeProperties properties, TransactionTemplate tx, Clock clock, ApplicationEventPublisher events,
            ResumeParseStore parseStore, AiUsageGate gate) {
        this.parseStore = parseStore;
        this.gate = gate;
        this.resumes = resumes;
        this.versions = versions;
        this.storage = storage;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    /** Where a user's files live; deleting this prefix removes everything stored for them. */
    static String storagePrefix(UUID userId) {
        return "resumes/" + userId + "/";
    }

    ResumeResponse upload(UUID userId, byte[] content, String originalFilename, String requestedLabel) {
        if (content.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_file", "The uploaded file is empty.");
        }
        if (content.length > properties.maxSize().toBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                    "CVs can be at most " + properties.maxSize().toMegabytes() + " MB.");
        }
        ResumeFormat format = FileSniffer.sniff(content).orElseThrow(() -> new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type", "Only PDF and DOCX files are accepted."));
        if (resumes.countByUserId(userId) >= properties.maxPerUser()) {
            throw new ApiException(HttpStatus.CONFLICT, "resume_limit_reached",
                    "You can keep at most " + properties.maxPerUser() + " CVs. Delete one to upload another.");
        }

        UUID id = UUID.randomUUID();
        // The key never contains anything the client sent.
        String key = storagePrefix(userId) + id + "." + format.extension();
        storage.put(key, content, format.contentType());
        try {
            Resume saved = tx.execute(status -> {
                boolean first = resumes.countByUserId(userId) == 0;
                Resume resume = resumes.save(
                        new Resume(id, userId, label(requestedLabel, originalFilename), key, format, content.length, first));
                versions.save(new ResumeVersion(id, 1, ResumeVersion.Source.UPLOAD));
                // Parsing is queued after this transaction commits (ResumeParseDispatcher).
                events.publishEvent(new ResumeUploaded(id, userId, 1));
                return resume;
            });
            return ResumeResponse.from(saved);
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
    }

    /**
     * Asks for the parse of a CV again after it failed for a reason that is not the file's fault: the daily AI cap
     * (usable again after the reset), or ai-service or the queue being unavailable. The cap is checked first, so
     * trying too early is answered 429 {@code ai_daily_cap_reached} with the reset time, without queuing anything.
     */
    ResumeResponse reparse(UUID userId, UUID id) {
        Resume resume = find(userId, id);
        ParseFailureReason reason = resume.getParseStatus() == ParseStatus.FAILED
                ? ParseFailureReason.fromCode(resume.getParseError()).orElse(null) : null;
        if (reason == null || !reason.userRetryable()) {
            throw new ApiException(HttpStatus.CONFLICT, "reparse_not_allowed",
                    "Only a CV whose parsing failed because of the daily limit or a temporary problem can be parsed again.");
        }
        gate.requireAllowance(userId, "parse_resume");
        if (!parseStore.retry(id, userId, reason)) {
            throw new ApiException(HttpStatus.CONFLICT, "reparse_not_allowed",
                    "This CV is already being parsed again.");
        }
        return ResumeResponse.from(find(userId, id));
    }

    List<ResumeResponse> list(UUID userId) {
        return resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().map(ResumeResponse::from).toList();
    }

    DownloadUrlResponse downloadUrl(UUID userId, UUID id) {
        Resume resume = find(userId, id);
        Duration ttl = properties.downloadUrlTtl();
        URI url = storage.presignDownload(resume.getFileKey(), downloadName(resume), ttl);
        return new DownloadUrlResponse(url.toString(), Instant.now(clock).plus(ttl));
    }

    ResumeResponse setPrimary(UUID userId, UUID id) {
        return tx.execute(status -> {
            Resume resume = find(userId, id);
            if (!resume.isPrimary()) {
                // The bulk update detaches loaded entities, so reload before flipping the new one.
                resumes.clearPrimary(userId);
                resume = find(userId, id);
                resume.setPrimary(true);
            }
            return ResumeResponse.from(resume);
        });
    }

    /**
     * Removes the row first, then the file: a failed file delete leaves an unreferenced private
     * object (logged), never a row that points at nothing. If the primary resume goes, the newest
     * remaining one takes over.
     */
    void delete(UUID userId, UUID id) {
        String key = tx.execute(status -> {
            Resume resume = find(userId, id);
            resumes.delete(resume);
            resumes.flush();
            if (resume.isPrimary()) {
                resumes.findFirstByUserIdOrderByCreatedAtDesc(userId).ifPresent(next -> next.setPrimary(true));
            }
            return resume.getFileKey();
        });
        deleteQuietly(key);
    }

    private Resume find(UUID userId, UUID id) {
        return resumes.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "resume_not_found", "Resume not found."));
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete stored file {}; it is now unreferenced", key, e);
        }
    }

    /** The label the user gave, else the uploaded file name without path or extension. */
    private static String label(String requested, String originalFilename) {
        String label = clean(requested);
        if (label.isEmpty() && originalFilename != null) {
            String name = originalFilename.substring(Math.max(originalFilename.lastIndexOf('/'),
                    originalFilename.lastIndexOf('\\')) + 1);
            int dot = name.lastIndexOf('.');
            label = clean(dot > 0 ? name.substring(0, dot) : name);
        }
        return label.isEmpty() ? "Resume" : label;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").strip();
        return cleaned.length() > MAX_LABEL_LENGTH ? cleaned.substring(0, MAX_LABEL_LENGTH).strip() : cleaned;
    }

    /** A header-safe download name derived from the label. */
    private static String downloadName(Resume resume) {
        String base = resume.getLabel().replaceAll("[^A-Za-z0-9 ._-]", "_").strip();
        return (base.isEmpty() ? "resume" : base) + "." + resume.getFileType().extension();
    }
}
