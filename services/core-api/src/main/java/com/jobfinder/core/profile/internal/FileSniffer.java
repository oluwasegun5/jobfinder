package com.jobfinder.core.profile.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what an upload really is from its bytes. The declared content type and file name
 * are ignored: they are attacker-controlled. PDFs are recognised by their header; DOCX by being
 * a ZIP whose central directory lists the parts every Word document has. The central directory
 * is read directly rather than unzipping, so a decompression bomb costs nothing.
 */
final class FileSniffer {

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_HEADER_SIGNATURE = 0x02014b50;
    private static final int EOCD_MIN_SIZE = 22;
    private static final int CENTRAL_HEADER_SIZE = 46;
    private static final int MAX_ENTRIES = 10_000;

    private FileSniffer() {
    }

    static Optional<ResumeFormat> sniff(byte[] bytes) {
        if (startsWith(bytes, PDF_MAGIC)) {
            return Optional.of(ResumeFormat.PDF);
        }
        if (isDocx(bytes)) {
            return Optional.of(ResumeFormat.DOCX);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDocx(byte[] bytes) {
        // Local file header signature "PK\3\4": a non-empty ZIP starts with it.
        if (bytes.length < EOCD_MIN_SIZE || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            return false;
        }
        Set<String> names = centralDirectoryNames(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
        return names.contains("[Content_Types].xml")
                && names.contains("word/document.xml")
                // A macro-enabled document (.docm) shares the layout but is not a plain CV.
                && !names.contains("word/vbaProject.bin");
    }

    /** Entry names from the ZIP central directory, or an empty set if the archive is malformed. */
    private static Set<String> centralDirectoryNames(ByteBuffer zip) {
        Set<String> names = new HashSet<>();
        int eocd = findEndOfCentralDirectory(zip);
        if (eocd < 0) {
            return names;
        }
        int entries = Short.toUnsignedInt(zip.getShort(eocd + 10));
        long offset = Integer.toUnsignedLong(zip.getInt(eocd + 16));
        if (entries > MAX_ENTRIES || offset >= eocd) {
            return names;
        }
        int position = (int) offset;
        for (int i = 0; i < entries; i++) {
            if (position + CENTRAL_HEADER_SIZE > zip.limit() || zip.getInt(position) != CENTRAL_HEADER_SIGNATURE) {
                return new HashSet<>();
            }
            int nameLength = Short.toUnsignedInt(zip.getShort(position + 28));
            int extraLength = Short.toUnsignedInt(zip.getShort(position + 30));
            int commentLength = Short.toUnsignedInt(zip.getShort(position + 32));
            int nameStart = position + CENTRAL_HEADER_SIZE;
            int next = nameStart + nameLength + extraLength + commentLength;
            if (next > zip.limit()) {
                return new HashSet<>();
            }
            byte[] name = new byte[nameLength];
            zip.get(nameStart, name);
            names.add(new String(name, StandardCharsets.UTF_8));
            position = next;
        }
        return names;
    }

    private static int findEndOfCentralDirectory(ByteBuffer zip) {
        // The record is 22 bytes plus a comment of at most 65535 bytes, and sits at the very end.
        int lowest = Math.max(0, zip.limit() - EOCD_MIN_SIZE - 0xFFFF);
        for (int i = zip.limit() - EOCD_MIN_SIZE; i >= lowest; i--) {
            if (zip.getInt(i) == EOCD_SIGNATURE) {
                return i;
            }
        }
        return -1;
    }
}
