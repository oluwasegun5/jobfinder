package com.jobfinder.core.profile.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class FileSnifferTests {

    static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] zip(String... entryNames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : entryNames) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static byte[] docx() throws IOException {
        return zip("[Content_Types].xml", "_rels/.rels", "word/document.xml");
    }

    @Test
    void recognisesPdfByItsHeader() {
        assertThat(FileSniffer.sniff(pdf())).contains(ResumeFormat.PDF);
    }

    @Test
    void recognisesDocxByItsParts() throws IOException {
        assertThat(FileSniffer.sniff(docx())).contains(ResumeFormat.DOCX);
    }

    @Test
    void rejectsAZipThatIsNotAWordDocument() throws IOException {
        assertThat(FileSniffer.sniff(zip("[Content_Types].xml", "xl/workbook.xml"))).isEmpty();
        assertThat(FileSniffer.sniff(zip("hello.txt"))).isEmpty();
    }

    @Test
    void rejectsMacroEnabledDocuments() throws IOException {
        assertThat(FileSniffer.sniff(zip("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin")))
                .isEmpty();
    }

    @Test
    void rejectsOtherFormatsRegardlessOfWhatTheyClaim() {
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };
        byte[] exe = "MZ\u0090\u0000\u0003\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
        byte[] text = "just some text pretending to be a resume".getBytes(StandardCharsets.UTF_8);
        for (byte[] content : new byte[][] { png, exe, text, new byte[0], new byte[] { '%' } }) {
            assertThat(FileSniffer.sniff(content)).isEqualTo(Optional.empty());
        }
    }

    @Test
    void aPdfMustStartWithItsHeader() {
        byte[] shifted = ("junk" + new String(pdf(), StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII);
        assertThat(FileSniffer.sniff(shifted)).isEmpty();
    }

    @Test
    void survivesTruncatedAndCorruptArchives() throws IOException {
        byte[] whole = docx();
        for (int length : new int[] { 4, 10, 21, 30, whole.length / 2, whole.length - 1 }) {
            assertThat(FileSniffer.sniff(Arrays.copyOf(whole, length))).isEmpty();
        }
        byte[] corrupt = whole.clone();
        Arrays.fill(corrupt, corrupt.length - 40, corrupt.length - 25, (byte) 0xFF);
        assertThat(FileSniffer.sniff(corrupt)).isEmpty();
    }
}
