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
    /** A CV that unpacks to more than this, or more than {@link #MAX_EXPANSION_RATIO} times its size, is a bomb. */
    static final long MAX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024;
    static final long MAX_EXPANSION_RATIO = 100;

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
        ByteBuffer zip = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Set<String> names = centralDirectoryNames(zip);
        return names.contains("[Content_Types].xml")
                && names.contains("word/document.xml")
                // A macro-enabled document (.docm) shares the layout but is not a plain CV, and neither is one with
                // ActiveX controls: both are code carriers.
                && !names.contains("word/vbaProject.bin") && !names.contains("word/vbaData.xml")
                && names.stream().noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("word/activex/"))
                && !expandsTooMuch(zip, bytes.length);
    }

    /**
     * Sums the uncompressed sizes the central directory declares (without unpacking anything) and refuses an archive
     * that claims to hold more than {@link #MAX_UNCOMPRESSED_BYTES}, or more than {@link #MAX_EXPANSION_RATIO} times
     * the file's own size: the parser downstream must never be handed a decompression bomb. A declared size can lie,
     * so the parser also unpacks with its own cap; this is the cheap first line.
     */
    private static boolean expandsTooMuch(ByteBuffer zip, int fileLength) {
        int eocd = findEndOfCentralDirectory(zip);
        if (eocd < 0) {
            return true;
        }
        int entries = Short.toUnsignedInt(zip.getShort(eocd + 10));
        int position = (int) Integer.toUnsignedLong(zip.getInt(eocd + 16));
        long total = 0;
        for (int i = 0; i < entries; i++) {
            if (position + CENTRAL_HEADER_SIZE > zip.limit() || zip.getInt(position) != CENTRAL_HEADER_SIGNATURE) {
                return true;
            }
            total += Integer.toUnsignedLong(zip.getInt(position + 24));
            if (total > MAX_UNCOMPRESSED_BYTES || total > Math.max(1, fileLength) * MAX_EXPANSION_RATIO) {
                return true;
            }
            position += CENTRAL_HEADER_SIZE + Short.toUnsignedInt(zip.getShort(position + 28))
                    + Short.toUnsignedInt(zip.getShort(position + 30)) + Short.toUnsignedInt(zip.getShort(position + 32));
        }
        return false;
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
