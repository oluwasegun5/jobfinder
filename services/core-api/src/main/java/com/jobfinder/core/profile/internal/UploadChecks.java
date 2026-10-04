package com.jobfinder.core.profile.internal;

import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.jobfinder.core.shared.ApiException;

/**
 * The upload rules that do not need the scanner (ASVS V12.1, V12.2): the file's bytes decide what it is
 * ({@link FileSniffer}), and the name and declared type the client sent must agree with that, so a PDF cannot hide
 * behind a {@code .txt} name and an executable cannot hide behind a PDF header. Only {@code pdf} and {@code docx}
 * extensions and their two media types (or the generic {@code application/octet-stream} some browsers send for DOCX) are
 * allowed; macro-enabled and archive formats are refused by the sniffer.
 */
final class UploadChecks {

    private static final Set<String> GENERIC_TYPES = Set.of("application/octet-stream", "binary/octet-stream");

    private UploadChecks() {
    }

    /** Throws 415 {@code file_type_mismatch} unless the extension and the declared type agree with the sniffed format. */
    static void requireAgreement(ResumeFormat sniffed, String originalFilename, String declaredType) {
        String extension = extensionOf(originalFilename);
        if (!sniffed.extension().equals(extension)) {
            throw mismatch();
        }
        String type = declaredType == null ? "" : declaredType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !type.equals(sniffed.contentType()) && !GENERIC_TYPES.contains(type)) {
            throw mismatch();
        }
    }

    /** The lower-cased text after the last dot of the last path segment, or empty. Never used as a path. */
    static String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        String name = originalFilename.substring(
                Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).strip().toLowerCase(Locale.ROOT);
    }

    private static ApiException mismatch() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file_type_mismatch",
                "The file name or type does not match the file's contents. Upload a .pdf or .docx file.");
    }
}
