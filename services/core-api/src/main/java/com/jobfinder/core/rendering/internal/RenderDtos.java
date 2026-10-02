package com.jobfinder.core.rendering.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request and response shapes of the render endpoints (docs/adr/0030-document-rendering.md). */
final class RenderDtos {

    private RenderDtos() {
    }

    /**
     * What to render. Every field is optional: the default is the ATS template as an A4 PDF.
     *
     * @param template  {@code ATS} (plain, parser-first) or {@code STYLED}
     * @param format    {@code PDF} or {@code DOCX}
     * @param pageSize  {@code A4} or {@code LETTER}
     */
    record RenderRequest(RenderTemplate template, RenderFormat format, PageFormat pageSize) {

        RenderRequest {
            template = template == null ? RenderTemplate.ATS : template;
            format = format == null ? RenderFormat.PDF : format;
            pageSize = pageSize == null ? PageFormat.A4 : pageSize;
        }

        static RenderRequest defaults() {
            return new RenderRequest(null, null, null);
        }
    }

    /**
     * A rendered file and a short-lived link to download it. {@code downloadUrl} is pre-signed (no token needed) and
     * stops working at {@code expiresAt}; ask again for a fresh one, which costs no new render. {@code cached} is
     * true when the file already existed.
     */
    record RenderedFileResponse(UUID id, RenderTemplate template, RenderFormat format, PageFormat pageSize,
            String filename, String contentType, long sizeBytes,
            @Schema(description = "Hex SHA-256 of the file, to verify a download") String sha256, String downloadUrl,
            Instant expiresAt, boolean cached) {
    }

    /** A rendered file in a listing: no link; ask for one at the download endpoint. */
    record RenderedFileSummary(UUID id, RenderTemplate template, RenderFormat format, PageFormat pageSize,
            String filename, String contentType, long sizeBytes, String sha256, Instant createdAt) {
    }
}
