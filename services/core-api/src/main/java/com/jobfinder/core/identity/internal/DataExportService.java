package com.jobfinder.core.identity.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;

/**
 * Builds the caller's data export: one zip with a folder per module (JSON for the data, the original files for CVs and
 * rendered documents), a manifest and a plain-language README. It is assembled in a temporary file first, so a failing
 * exporter produces an error response rather than a truncated zip, and the file is removed as soon as it was sent.
 * Every exporter is given the id of the user who asked and nothing else.
 */
@Service
class DataExportService {

    static final String ROOT = "jobfinder-export/";

    private final List<UserDataExporter> exporters;
    private final Clock clock;

    DataExportService(List<UserDataExporter> exporters, Clock clock) {
        this.exporters = exporters.stream().sorted(java.util.Comparator.comparing(UserDataExporter::module)).toList();
        this.clock = clock;
    }

    /** A finished export on disk; {@link #close()} deletes it. */
    record Export(Path file, long size) implements AutoCloseable {
        @Override
        public void close() {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    Export build(UUID userId) {
        Path file = null;
        try {
            file = Files.createTempFile("jobfinder-export-", ".zip");
            try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
                Bundle bundle = new Bundle(zip);
                for (UserDataExporter exporter : exporters) {
                    bundle.module = exporter.module();
                    exporter.export(userId, bundle);
                }
                bundle.finish(clock.instant().toString());
            }
            return new Export(file, Files.size(file));
        } catch (IOException e) {
            deleteQuietly(file);
            throw new UncheckedIOException("Could not build the data export", e);
        } catch (RuntimeException e) {
            deleteQuietly(file);
            throw e;
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // The temp directory is cleaned by the OS; the original failure is what matters.
            }
        }
    }

    private static final class Bundle implements UserDataBundle {

        private final ZipOutputStream zip;
        private final Set<String> names = new HashSet<>();
        private final List<String> entries = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        String module = "";

        Bundle(ZipOutputStream zip) {
            this.zip = zip;
        }

        @Override
        public void json(String name, String json) {
            put(module + "/" + name + ".json", json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void file(String path, byte[] content) {
            put(module + "/" + path, content);
        }

        @Override
        public void skipped(String path, String reason) {
            skipped.add(module + "/" + path + ": " + reason);
        }

        private void put(String path, byte[] content) {
            if (path.contains("..") || path.startsWith("/") || !names.add(path)) {
                throw new IllegalArgumentException("Unusable export path: " + path);
            }
            write(path, content);
            entries.add(path);
        }

        private void write(String path, byte[] content) {
            try {
                zip.putNextEntry(new ZipEntry(ROOT + path));
                zip.write(content);
                zip.closeEntry();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void finish(String generatedAt) {
            StringBuilder manifest = new StringBuilder("{\n  \"generatedAt\": \"").append(generatedAt)
                    .append("\",\n  \"format\": 1,\n  \"files\": [");
            for (int i = 0; i < entries.size(); i++) {
                manifest.append(i == 0 ? "\n    \"" : ",\n    \"").append(entries.get(i)).append('"');
            }
            manifest.append("\n  ],\n  \"notIncluded\": [");
            for (int i = 0; i < skipped.size(); i++) {
                manifest.append(i == 0 ? "\n    \"" : ",\n    \"").append(skipped.get(i).replace("\"", "'")).append('"');
            }
            manifest.append("\n  ]\n}\n");
            write("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            write("README.txt", README.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final String README = """
            Your JobFinder data export
            ==========================

            This archive holds the personal data JobFinder stores about the account that requested it.

            - One folder per part of the product (account, profile, documents, applications, ...).
            - *.json files are your data as stored, one array of records per file.
            - Your uploaded CVs and the PDF/DOCX files generated for you are included as the original files.
            - manifest.json lists every file, and anything that exists but could not be added (notIncluded).

            Not included, on purpose: password hashes, session tokens, search vectors (numbers derived from your
            CV text) and internal storage paths. Payment card details are never held by JobFinder: they stay with
            the payment provider.

            Questions or requests about your data: see the privacy policy.
            """;
}
