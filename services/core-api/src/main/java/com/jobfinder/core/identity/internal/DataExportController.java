package com.jobfinder.core.identity.internal;

import java.io.InputStream;
import java.nio.file.Files;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.jobfinder.core.identity.CurrentUser;

/** The right of access and portability: the caller downloads everything stored about them as one zip. */
@RestController
class DataExportController {

    private final DataExportService exports;

    DataExportController(DataExportService exports) {
        this.exports = exports;
    }

    /** The export of the authenticated user, and only theirs: the id comes from the access token, never the request. */
    @GetMapping(value = "/me/export", produces = "application/zip")
    ResponseEntity<StreamingResponseBody> export() {
        DataExportService.Export export = exports.build(CurrentUser.require().id());
        StreamingResponseBody body = out -> {
            try (export; InputStream in = Files.newInputStream(export.file())) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(export.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("jobfinder-data-export.zip").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
