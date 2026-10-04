package com.jobfinder.core.storage.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.jobfinder.core.shared.ApiException;
import com.jobfinder.core.storage.UploadScanner;
import com.jobfinder.core.storage.UploadScannerUnavailableException;
import com.jobfinder.core.storage.UploadScans;

/** Runs the scanner and applies the configured failure policy. Logs signatures, never file names or content. */
final class DefaultUploadScans implements UploadScans {

    private static final Logger log = LoggerFactory.getLogger(DefaultUploadScans.class);

    private final UploadScanner scanner;
    private final UploadScanProperties.OnError onError;

    DefaultUploadScans(UploadScanner scanner, UploadScanProperties.OnError onError) {
        this.scanner = scanner;
        this.onError = onError;
    }

    @Override
    public void requireClean(byte[] content) {
        UploadScanner.Verdict verdict;
        try {
            verdict = scanner.scan(content);
        } catch (UploadScannerUnavailableException e) {
            if (onError == UploadScanProperties.OnError.open) {
                log.warn("Upload scanner unavailable; accepting the upload unscanned (fail-open): {}", e.getMessage());
                return;
            }
            log.error("Upload scanner unavailable; refusing the upload (fail-closed): {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "upload_scan_unavailable",
                    "We could not check this file right now. Try again shortly.");
        }
        if (verdict.infected()) {
            log.warn("Upload refused: scanner reported {}", verdict.signature());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "upload_infected",
                    "This file was rejected by our security scan.");
        }
    }
}
