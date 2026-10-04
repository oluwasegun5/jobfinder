package com.jobfinder.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.jayway.jsonpath.JsonPath;

class ResumeUploadTests extends ResumeTestSupport {

    @Test
    void uploadingAPdfStoresItPrivatelyAndCreatesTheFirstVersion() throws Exception {
        Session session = newSession();

        upload(session, "Ada Lovelace CV.pdf", "application/pdf", pdf())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Ada Lovelace CV"))
                .andExpect(jsonPath("$.fileType").value("PDF"))
                .andExpect(jsonPath("$.sizeBytes").value(pdf().length))
                .andExpect(jsonPath("$.primary").value(true))
                .andExpect(jsonPath("$.parseStatus").value("PENDING"));

        UUID userId = userIdOf(session.accessToken());
        String key = jdbc.queryForObject("select file_key from resumes where user_id = ?", String.class, userId);
        assertThat(key).startsWith("resumes/" + userId + "/").endsWith(".pdf").doesNotContain("Lovelace");
        assertThat(objectExists(key)).isTrue();
        assertThat(count("select count(*) from resume_versions rv join resumes r on r.id = rv.resume_id "
                + "where r.user_id = ? and rv.version_number = 1 and rv.source = 'UPLOAD'", userId)).isEqualTo(1);
    }

    @Test
    void docxIsAccepted() throws Exception {
        upload(newSession(), "cv.docx", "application/octet-stream", docx())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileType").value("DOCX"));
    }

    @Test
    void theDeclaredTypeAndExtensionAreIgnored() throws Exception {
        Session session = newSession();
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };
        byte[] notAWordDocument = "PK\u0003\u0004 not really a zip".getBytes();

        upload(session, "cv.pdf", "application/pdf", png).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_file_type"));
        upload(session, "cv.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                notAWordDocument).andExpect(status().isUnsupportedMediaType());
        // The bytes decide what a file is (a PDF stays a PDF), but since ADR 0037 the name and declared type must also
        // agree with them: a real PDF under a .txt name and text/plain is refused (UploadSafetyTests covers the rest).
        upload(session, "notes.txt", "text/plain", pdf()).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("file_type_mismatch"));
        upload(session, "notes.pdf", "application/octet-stream", pdf()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileType").value("PDF"));
        assertThat(count("select count(*) from resumes where user_id = ?", userIdOf(session.accessToken())))
                .isEqualTo(1);
    }

    @Test
    void emptyFilesAreRejected() throws Exception {
        upload(newSession(), "cv.pdf", "application/pdf", new byte[0]).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("empty_file"));
    }

    @Test
    void filesOverFiveMegabytesAreRejectedButExactlyFiveIsFine() throws Exception {
        Session session = newSession();
        byte[] atLimit = new byte[5 * 1024 * 1024];
        System.arraycopy(pdf(), 0, atLimit, 0, pdf().length);
        byte[] overLimit = new byte[atLimit.length + 1];
        System.arraycopy(pdf(), 0, overLimit, 0, pdf().length);

        upload(session, "big.pdf", "application/pdf", overLimit).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("file_too_large"));
        upload(session, "max.pdf", "application/pdf", atLimit).andExpect(status().isCreated());
    }

    @Test
    void theFirstResumeIsPrimaryAndLaterOnesAreNot() throws Exception {
        Session session = newSession();
        upload(session, "one.pdf", "application/pdf", pdf()).andExpect(jsonPath("$.primary").value(true));
        upload(session, "two.pdf", "application/pdf", pdf()).andExpect(jsonPath("$.primary").value(false));
    }

    @Test
    void labelCanBeGivenAndIsCleaned() throws Exception {
        Session session = newSession();
        String body = mvc.perform(uploadRequest(session, "../../etc/passwd.pdf", "application/pdf", pdf())
                .param("label", "  Backend\r\nrole  ")).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.label")).isEqualTo("Backend  role");

        String fallback = upload(session, "../../etc/passwd.pdf", "application/pdf", pdf()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<String>read(fallback, "$.label")).isEqualTo("passwd");
    }

    @Test
    void thereIsALimitOnHowManyResumesOneUserCanKeep() throws Exception {
        Session session = newSession();
        for (int i = 0; i < 10; i++) {
            upload(session, "cv" + i + ".pdf", "application/pdf", pdf()).andExpect(status().isCreated());
        }
        upload(session, "cv10.pdf", "application/pdf", pdf()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resume_limit_reached"));
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        mvc.perform(multipart("/resumes")
                .file(new MockMultipartFile("file", "cv.pdf", "application/pdf", pdf())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDownloadLinkFetchesTheOriginalFileAsAnAttachment() throws Exception {
        Session session = newSession();
        UUID id = uploadPdf(session);

        String body = mvc.perform(get("/resumes/" + id + "/download-url").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").exists()).andReturn().getResponse()
                .getContentAsString();

        HttpResponse<byte[]> download = httpGet(JsonPath.read(body, "$.url"));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(pdf());
        assertThat(download.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
                .startsWith("attachment").contains("cv.pdf");
    }
}
