package com.jobfinder.core.rendering.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jobfinder.core.documents.ApprovedDocument;
import com.jobfinder.core.documents.ApprovedDocuments;
import com.jobfinder.core.profile.ResumeContents;
import com.jobfinder.core.profile.ResumeSnapshot;
import com.jobfinder.core.rendering.internal.RenderDtos.RenderRequest;
import com.jobfinder.core.rendering.internal.RenderDtos.RenderedFileResponse;
import com.jobfinder.core.rendering.internal.RenderDtos.RenderedFileSummary;
import com.jobfinder.core.rendering.internal.RenderedFileStore.SourceType;
import com.jobfinder.core.rendering.internal.RenderedFileStore.Variant;
import com.jobfinder.core.shared.ApiException;
import com.jobfinder.core.storage.ObjectStorage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders resumes to PDF or DOCX, caches the files and hands out download links (docs/adr/0030-document-rendering.md).
 * Every method takes the caller's user ID and scopes by it: someone else's document or resume is a 404.
 *
 * <p>Not transactional as a whole, like the CV service: object storage is not part of the database transaction, so
 * the file is written first and the index row second (a failed row leaves an unreferenced private file at a key that
 * the next identical request writes again, never a row that points at nothing).
 */
@Service
class RenderService {

    /** Bump when a template changes what it prints: files rendered by an older look are then never served. */
    static final int RENDERER_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(RenderService.class);

    private final ApprovedDocuments documents;
    private final ResumeContents resumes;
    private final ResumeRenderer renderer;
    private final RenderedFileStore store;
    private final ObjectStorage storage;
    private final RenderingProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    RenderService(ApprovedDocuments documents, ResumeContents resumes, ResumeRenderer renderer,
            RenderedFileStore store, ObjectStorage storage, RenderingProperties properties, JsonMapper json,
            Clock clock) {
        this.documents = documents;
        this.resumes = resumes;
        this.renderer = renderer;
        this.store = store;
        this.storage = storage;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /** Where a user's rendered files live; deleting this prefix removes all of them. */
    static String storagePrefix(UUID userId) {
        return "renders/" + userId + "/";
    }

    private ApprovedDocument approvedDocument(UUID userId, UUID documentId) {
        return documents.approved(userId, documentId).orElseThrow(() -> {
            if (documents.exists(userId, documentId)) {
                return new ApiException(HttpStatus.CONFLICT, "document_not_approved",
                        "Only an approved document can be rendered. Review and approve the draft first.");
            }
            return new ApiException(HttpStatus.NOT_FOUND, "document_not_found", "Document not found.");
        });
    }

    /** An approved document (never a draft). 404 if it is not the caller's, 409 {@code document_not_approved}. */
    Result renderDocument(UUID userId, UUID documentId, RenderRequest request) {
        ApprovedDocument document = approvedDocument(userId, documentId);
        if (!"TAILORED_RESUME".equals(document.type())) {
            throw new ApiException(HttpStatus.CONFLICT, "document_not_renderable",
                    "This kind of document cannot be rendered as a resume.");
        }
        return render(userId, SourceType.DOCUMENT, document.id(), document.contentJson(), document.jobCompany(),
                request);
    }

    /** The files rendered so far from an approved document (same 404 and 409 as rendering it). */
    List<RenderedFileSummary> listDocumentFiles(UUID userId, UUID documentId) {
        ApprovedDocument document = approvedDocument(userId, documentId);
        String name = fileModel(document).name();
        return store.list(userId, SourceType.DOCUMENT, document.id()).stream().map(row -> new RenderedFileSummary(
                row.id(), row.template(), row.format(), row.pageSize(),
                FileNames.of(name, document.jobCompany(), row.format().extension()), row.format().contentType(),
                row.sizeBytes(), row.fileSha256(), row.createdAt())).toList();
    }

    /** A fresh short-lived link to a file rendered from the caller's approved document; 404 for any other file. */
    RenderedFileResponse downloadDocumentFile(UUID userId, UUID documentId, UUID fileId) {
        ApprovedDocument document = approvedDocument(userId, documentId);
        RenderedFileStore.Row row = store.get(userId, SourceType.DOCUMENT, document.id(), fileId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "file_not_found", "File not found."));
        if (!storage.exists(row.storageKey())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file_not_found",
                    "This file is no longer available; render it again.");
        }
        return respond(row, fileModel(document), document.jobCompany(), true).file();
    }

    private ResumeModel fileModel(ApprovedDocument document) {
        return ResumeModel.parse(parse(document.contentJson()));
    }

    /** The latest content of one of the caller's own resumes, whether tailored or not. */
    Result renderResume(UUID userId, UUID resumeId, RenderRequest request) {
        ResumeSnapshot resume = resumes.latest(userId, resumeId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "resume_not_found", "Resume not found."));
        if (resume.structuredJson() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "resume_content_required",
                    "This CV has no content yet. Wait for it to be parsed, or fill it in, then export it.");
        }
        return render(userId, SourceType.RESUME_VERSION, resume.versionId(), resume.structuredJson(), null, request);
    }

    private Result render(UUID userId, SourceType sourceType, UUID sourceId, String contentJson, String company,
            RenderRequest request) {
        JsonNode content = parse(contentJson);
        ResumeModel model = ResumeModel.parse(content);
        Variant variant = new Variant(userId, sourceType, sourceId, sha256(contentJson.getBytes(StandardCharsets.UTF_8)),
                request.template(), request.format(), request.pageSize());

        Optional<RenderedFileStore.Row> cached = store.find(variant, RENDERER_VERSION);
        if (cached.isPresent()) {
            if (storage.exists(cached.get().storageKey())) {
                return respond(cached.get(), model, company, true);
            }
            // The index row outlived its file (removed out of band): render again and write it back.
            log.warn("Rendered file {} is missing from storage; rendering it again", cached.get().storageKey());
            store.delete(cached.get().id());
        }

        byte[] bytes = renderer.render(model, request.template(), request.format(), request.pageSize());
        String key = storagePrefix(userId) + sourceId + "/" + request.template().slug() + "-"
                + request.pageSize().slug() + "-" + variant.contentSha256().substring(0, 16) + "-r"
                + RENDERER_VERSION + "." + request.format().extension();
        storage.put(key, bytes, request.format().contentType());
        String fileHash = sha256(bytes);
        Instant now = Instant.now(clock);
        try {
            if (!store.insert(variant, RENDERER_VERSION, key, fileHash, bytes.length, now)) {
                // A concurrent identical request got there first; same key, same bytes. Serve its row.
                return respond(store.find(variant, RENDERER_VERSION).orElseThrow(), model, company, true);
            }
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
        RenderedFileStore.Row row = store.find(variant, RENDERER_VERSION).orElseThrow();
        return respond(row, model, company, false);
    }

    private Result respond(RenderedFileStore.Row row, ResumeModel model, String company, boolean cached) {
        String filename = FileNames.of(model.name(), company, row.format().extension());
        Duration ttl = properties.downloadUrlTtl();
        URI url = storage.presignDownload(row.storageKey(), filename, ttl);
        return new Result(new RenderedFileResponse(row.id(), row.template(), row.format(), row.pageSize(), filename,
                row.format().contentType(), row.sizeBytes(), row.fileSha256(), url.toString(),
                Instant.now(clock).plus(ttl), cached), !cached);
    }

    private JsonNode parse(String contentJson) {
        try {
            return json.readTree(contentJson);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored resume content is unreadable", e);
        }
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete rendered file {}; it is now unreferenced", key, e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The response, and whether this request rendered the file (201) or found it (200). */
    record Result(RenderedFileResponse file, boolean created) {
    }
}
