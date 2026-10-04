package com.jobfinder.core.storage.internal;

import com.jobfinder.core.storage.UploadScanner;

/** Development and test adapter: every file is clean. Never the production setting (see docs/adr/0037). */
final class NoOpUploadScanner implements UploadScanner {

    @Override
    public Verdict scan(byte[] content) {
        return Verdict.clean();
    }
}
