package com.jobfinder.core.rendering.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.documents.WritingTestSupport;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * POST /documents/{id}/render for a cover letter (P4.3 on the P4.2 renderer): an approved letter renders to a PDF and a
 * DOCX in both templates with its own file name, extracts back in reading order, reflects the user's edits, is cached
 * like a resume, is refused as a draft, screening answers are not renderable (structured JSON from GET only), another
 * user's letter is a 404, and account deletion removes the files.
 */
class CoverLetterRenderEndpointTests extends WritingTestSupport {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PDF = "{\"template\":\"ATS\",\"format\":\"PDF\",\"pageSize\":\"A4\"}";
    private static final String OPENING = "I am writing to apply for the Backend Engineer role at Harbor Freight Tech.";

    @MockitoSpyBean
    private ResumeRenderer renderer;

    @Autowired
    private S3Client s3;

    @Value("${app.storage.bucket}")
    private String bucket;

    private record Letter(Session session, Candidate candidate, String id) {
    }

    private Letter letter(boolean approved) throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubTextCheck(candidate);
        stubLetter(candidate.userId(), letterOk(candidate, UUID.randomUUID(), "0.003"));
        String id = idOf(coverLetter(me, newJob(), null).andExpect(status().isCreated()));
        if (approved) {
            approve(me, id).andExpect(status().isOk());
        }
        return new Letter(me, candidate, id);
    }

    private ResultActions render(Session session, String documentId, String json) throws Exception {
        return mvc.perform(post("/documents/" + documentId + "/render").header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String field(ResultActions result, String path) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), path));
    }

    private HttpResponse<byte[]> download(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private long objectsUnder(String prefix) {
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()).keyCount();
    }

    @Test
    void anApprovedLetterRendersToAPdfThatExtractsInReadingOrder() throws Exception {
        Letter a = letter(true);

        ResultActions result = render(a.session(), a.id(), PDF).andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("PDF")).andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.cached").value(false));
        String filename = field(result, "$.filename");

        assertThat(filename).isEqualTo("Jordan_" + a.candidate().name().substring(7) + "_Cover_Letter_Acme_Test_Co.pdf");
        HttpResponse<byte[]> response = download(field(result, "$.downloadUrl"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                .contains("filename=\"" + filename + "\"");
        String text = PdfRenderingTests.flat(PdfRenderingTests.text(response.body()));
        assertThat(text).startsWith(a.candidate().name());
        PdfRenderingTests.assertInOrder(text, a.candidate().name(), "jordan.ikeji@example.test", "Acme Test Co",
                "Re: Application for Staff Backend Engineer", "Dear Hiring Manager,", OPENING,
                "At Northwind Systems I led a team of 4 engineers", "I would welcome the chance to talk.",
                "Yours sincerely,");
        assertThat(text).endsWith(a.candidate().name());
    }

    @Test
    void theSameRequestAgainRendersNothingAndAnotherTemplateIsAnotherFile() throws Exception {
        Letter a = letter(true);
        ResultActions first = render(a.session(), a.id(), PDF).andExpect(status().isCreated());
        String firstId = field(first, "$.id");

        render(a.session(), a.id(), PDF).andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.id").value(firstId));
        verify(renderer, times(1)).render(any(), any(), any(), any());
        ResultActions styled = render(a.session(), a.id(), "{\"template\":\"STYLED\",\"pageSize\":\"LETTER\"}")
                .andExpect(status().isCreated());

        assertThat(field(styled, "$.id")).isNotEqualTo(firstId);
        mvc.perform(get("/documents/" + a.id() + "/files").header("Authorization", bearer(a.session())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].filename").value(org.hamcrest.Matchers.containsString("_Cover_Letter_")))
                .andExpect(jsonPath("$[1].filename").value(org.hamcrest.Matchers.containsString("_Cover_Letter_")));
        ResultActions link = mvc.perform(get("/documents/" + a.id() + "/files/" + firstId + "/download")
                .header("Authorization", bearer(a.session()))).andExpect(status().isOk());
        assertThat(download(field(link, "$.downloadUrl")).statusCode()).isEqualTo(200);
    }

    @Test
    void anApprovedLetterRendersToADocxWithItsParagraphsInOrder() throws Exception {
        Letter a = letter(true);

        ResultActions result = render(a.session(), a.id(), "{\"template\":\"STYLED\",\"format\":\"DOCX\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.format").value("DOCX"));

        HttpResponse<byte[]> response = download(field(result, "$.downloadUrl"));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(response.body()))) {
            List<String> paragraphs = document.getParagraphs().stream().map(XWPFParagraph::getText).toList();
            assertThat(paragraphs.get(0)).isEqualTo(a.candidate().name());
            assertThat(paragraphs).containsSubsequence("Acme Test Co", "Re: Application for Staff Backend Engineer",
                    "Dear Hiring Manager,", "Yours sincerely,", a.candidate().name());
            int salutation = paragraphs.indexOf("Dear Hiring Manager,");
            int opening = paragraphs.stream().filter(p -> p.startsWith(OPENING)).mapToInt(paragraphs::indexOf).findFirst()
                    .orElse(-1);
            assertThat(opening).isGreaterThan(salutation).isLessThan(paragraphs.indexOf("Yours sincerely,"));
        }
    }

    @Test
    void theFileCarriesTheEditsTheUserMadeBeforeApproving() throws Exception {
        Letter a = letter(false);
        patchAs(a.session(), a.id(), edit(1, "paragraphs[2]", "I would be glad to talk it through, any time."))
                .andExpect(status().isOk());
        approve(a.session(), a.id()).andExpect(status().isOk());

        ResultActions result = render(a.session(), a.id(), PDF).andExpect(status().isCreated());

        String text = PdfRenderingTests.flat(PdfRenderingTests.text(download(field(result, "$.downloadUrl")).body()));
        assertThat(text).contains("I would be glad to talk it through, any time.")
                .doesNotContain("I would welcome the chance to talk.");
    }

    @Test
    void aDraftLetterCannotBeRendered() throws Exception {
        Letter a = letter(false);

        render(a.session(), a.id(), PDF).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_not_approved"));

        assertThat(jdbc.queryForObject("select count(*) from rendered_files where user_id = ?", Integer.class,
                a.candidate().userId())).isZero();
    }

    @Test
    void screeningAnswersAreNotRenderableOnlyReadAsJson() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubTextCheck(candidate);
        stubAnswers(candidate.userId(), answersOk(candidate, UUID.randomUUID(), "0.002"));
        String id = idOf(screeningAnswers(me, newJob(), null).andExpect(status().isCreated()));
        patchAs(me, id, edit(1, "answers.NOTICE_PERIOD", "Two months.")).andExpect(status().isOk());
        patchAs(me, id, edit(2, "answers.WORK_AUTHORIZATION", "Authorized, no sponsorship needed."))
                .andExpect(status().isOk());
        approve(me, id).andExpect(status().isOk());

        render(me, id, PDF).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_not_renderable"));
        mvc.perform(get("/documents/" + id + "/files").header("Authorization", bearer(me)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("document_not_renderable"));
        getDocument(me, id).andExpect(status().isOk()).andExpect(jsonPath("$.content.answers.length()").value(10))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'NOTICE_PERIOD')].answer").value("Two months."));
    }

    @Test
    void anotherUsersLetterIsNotFound() throws Exception {
        Letter a = letter(true);
        Session other = newSession();
        seed(other);

        render(other, a.id(), PDF).andExpect(status().isNotFound());
        mvc.perform(get("/documents/" + a.id() + "/files").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingTheAccountRemovesTheLetterFilesAndOnlyThose() throws Exception {
        Letter leaving = letter(true);
        Letter bystander = letter(true);
        render(leaving.session(), leaving.id(), PDF).andExpect(status().isCreated());
        render(leaving.session(), leaving.id(), "{\"format\":\"DOCX\"}").andExpect(status().isCreated());
        render(bystander.session(), bystander.id(), PDF).andExpect(status().isCreated());
        assertThat(objectsUnder("renders/" + leaving.candidate().userId() + "/")).isEqualTo(2);

        mvc.perform(delete("/me").header("Authorization", bearer(leaving.session()))).andExpect(status().isNoContent());

        assertThat(objectsUnder("renders/" + leaving.candidate().userId() + "/")).isZero();
        assertThat(documents(leaving.candidate().userId())).isZero();
        assertThat(objectsUnder("renders/" + bystander.candidate().userId() + "/")).isEqualTo(1);
    }
}
