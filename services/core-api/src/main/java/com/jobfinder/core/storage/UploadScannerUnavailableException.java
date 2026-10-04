package com.jobfinder.core.storage;

/** The scan engine could not be reached, timed out, or answered with something other than a verdict. */
public class UploadScannerUnavailableException extends RuntimeException {

    public UploadScannerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
