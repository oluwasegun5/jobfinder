package com.jobfinder.core.storage;

/**
 * The port every file upload passes through before anything is stored or parsed (ASVS V12.1.2, V12.4.2). An adapter
 * decides one thing: is this content known malware. It never sees a file name and never logs content.
 *
 * <p>Adapters: a no-op for development and tests (the default), and a ClamAV-compatible one that streams the bytes to a
 * {@code clamd} daemon (docs/adr/0037-security-hardening.md).
 */
public interface UploadScanner {

    /** The result of scanning: clean, or infected with the engine's signature name (for the log, never for the user). */
    record Verdict(boolean infected, String signature) {

        public static Verdict clean() {
            return new Verdict(false, null);
        }

        public static Verdict infected(String signature) {
            return new Verdict(true, signature);
        }
    }

    /** @throws UploadScannerUnavailableException if the engine cannot be reached or gives an answer we cannot trust */
    Verdict scan(byte[] content);
}
