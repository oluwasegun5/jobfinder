package com.jobfinder.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/**
 * Work item 5, the rules that hold with no scanner at all: size, extension and declared type must agree with the bytes,
 * no macro-enabled or archive formats, no decompression bombs, a file name is never a path, and stored objects come back
 * only through short-lived signed links that force an attachment. The scanner port itself is in
 * {@link UploadScannerIntegrationTests}.
 */
class UploadSafetyTests extends ResumeTestSupport {

    private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Test
    void aRealPdfUnderATextNameAndTypeIsRefusedBecauseTheyDoNotAgree() throws Exception {
        Session session = newSession();

        upload(session, "notes.txt", "text/plain", pdf()).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("file_type_mismatch"));
        upload(session, "cv.pdf", "text/html", pdf()).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("file_type_mismatch"));
        upload(session, "cv", "application/pdf", pdf()).andExpect(status().isUnsupportedMediaType());
        upload(session, "cv.pdf.exe", "application/pdf", pdf()).andExpect(status().isUnsupportedMediaType());
        assertThat(count("select count(*) from resumes where user_id = ?", userIdOf(session.accessToken()))).isZero();
    }

    @Test
    void aDocxDeclaredAsPdfAndAPdfDeclaredAsDocxAreRefused() throws Exception {
        Session session = newSession();

        upload(session, "cv.pdf", "application/pdf", docx()).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("file_type_mismatch"));
        upload(session, "cv.docx", DOCX_TYPE, pdf()).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("file_type_mismatch"));
    }

    @Test
    void theAllowedCombinationsStillWork() throws Exception {
        Session session = newSession();

        upload(session, "CV.PDF", "application/pdf; charset=binary", pdf()).andExpect(status().isCreated());
        upload(session, "cv.docx", DOCX_TYPE, docx()).andExpect(status().isCreated());
        upload(session, "cv.docx", "application/octet-stream", docx()).andExpect(status().isCreated());
    }

    @Test
    void macroEnabledWordDocumentsAreRefused() throws Exception {
        Session session = newSession();
        byte[] docm = zip("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin");

        upload(session, "cv.docm", "application/vnd.ms-word.document.macroEnabled.12", docm)
                .andExpect(status().isUnsupportedMediaType());
        // The same bytes under a .docx name and the docx type are still refused: the bytes decide.
        upload(session, "cv.docx", DOCX_TYPE, docm).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_file_type"));
        upload(session, "cv.docx", DOCX_TYPE, zip("[Content_Types].xml", "word/document.xml", "word/activeX/activeX1.xml"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void archivesAreRefusedWhateverTheyAreCalled() throws Exception {
        Session session = newSession();
        byte[] archive = zip("payload.exe", "readme.txt");

        upload(session, "cv.zip", "application/zip", archive).andExpect(status().isUnsupportedMediaType());
        upload(session, "cv.docx", DOCX_TYPE, archive).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_file_type"));
        upload(session, "cv.pdf", "application/pdf", new byte[] { 0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0 })
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aDecompressionBombIsRefusedBeforeAnythingUnpacksIt() throws Exception {
        Session session = newSession();
        // 60 MB of zeros deflates to about 60 KB: well under the 5 MB upload cap, far over the expansion limit.
        byte[] bomb = bomb(60 * 1024 * 1024);
        assertThat(bomb.length).isLessThan(5 * 1024 * 1024);

        upload(session, "cv.docx", DOCX_TYPE, bomb).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_file_type"));
        assertThat(count("select count(*) from resumes where user_id = ?", userIdOf(session.accessToken()))).isZero();
    }

    @Test
    void theClientsFileNameNeverReachesTheStorageKeyOrTheDatabaseUnsanitised() throws Exception {
        Session session = newSession();
        String evil = "..\\..\\..\\etc\\passwd\u0000/../cv\r\n.pdf";

        String body = upload(session, evil, "application/pdf", pdf()).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(fileKey(id)).matches("resumes/[0-9a-f-]{36}/[0-9a-f-]{36}\\.pdf");
        assertThat(JsonPath.<String>read(body, "$.label")).doesNotContain("\r", "\n", "\u0000", "/", "\\");
    }

    @Test
    void storedFilesComeBackOnlyThroughAShortLivedSignedLinkThatForcesAnAttachment() throws Exception {
        Session session = newSession();
        UUID id = uploadPdf(session);

        String json = mvc.perform(get("/resumes/" + id + "/download-url").header("Authorization",
                "Bearer " + session.accessToken())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String url = JsonPath.read(json, "$.url");

        assertThat(url).contains("X-Amz-Signature=").contains("X-Amz-Expires=300");
        HttpResponse<byte[]> signed = httpGet(url);
        assertThat(signed.statusCode()).isEqualTo(200);
        assertThat(signed.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment");
        assertThat(signed.headers().firstValue("Content-Type")).hasValue("application/pdf");
    }

    @Test
    void aSignedLinkCannotBeAskedToLiveLongerThanFifteenMinutes() {
        com.jobfinder.core.storage.ObjectStorage storage = applicationContext
                .getBean(com.jobfinder.core.storage.ObjectStorage.class);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> storage.presignDownload("resumes/x/y.pdf", "cv.pdf",
                        java.time.Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.presignDownload("resumes/x/y.pdf", "cv.pdf", java.time.Duration.ofMinutes(15))).isNotNull();
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext applicationContext;

    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] bomb(int uncompressedBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : new String[] { "[Content_Types].xml", "word/document.xml" }) {
                zip.putNextEntry(new ZipEntry(name));
                byte[] zeros = new byte[1024 * 1024];
                Arrays.fill(zeros, (byte) '0');
                for (int written = 0; written < uncompressedBytes / 2; written += zeros.length) {
                    zip.write(zeros);
                }
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
