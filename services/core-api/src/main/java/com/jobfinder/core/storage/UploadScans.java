package com.jobfinder.core.storage;

/**
 * The module's entry point for uploads: runs the configured {@link UploadScanner} and applies the failure policy.
 * Call it with the bytes, before they are written to object storage or handed to a parser.
 */
public interface UploadScans {

    /**
     * @throws com.jobfinder.core.shared.ApiException 422 {@code upload_infected} when the scanner flags the content;
     *                                                503 {@code upload_scan_unavailable} when the scanner is down and
     *                                                the policy is fail-closed (the default). Fail-open logs and lets
     *                                                the upload continue.
     */
    void requireClean(byte[] content);
}
