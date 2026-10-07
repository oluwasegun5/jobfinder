package com.jobfinder.core.rendering.internal;

import com.jobfinder.core.CoversEndpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.documents.DocumentsTestSupport;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The render endpoints end to end against real Postgres and S3Mock: what is rendered, where it is kept, that a second
 * request renders nothing, that the pre-signed link serves the identical bytes, who may render what, and that
 * account deletion removes the files.
 */
class RenderEndpointTests extends DocumentsTestSupport {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PDF = "{\"template\":\"ATS\",\"format\":\"PDF\",\"pageSize\":\"A4\"}";

    @MockitoSpyBean
    private ResumeRenderer renderer;

    @Autowired
    private S3Client s3;

    @Value("${app.storage.bucket}")
    private String bucket;

    // ------------------------------------------------------------------------------------------------- fixtures

    private record Approved(Session session, Candidate candidate, String id) {
    }

    private Approved approvedDocument() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubFactCheck(candidate);
        stubTailor(candidate.userId(), tailorOk(UUID.randomUUID(), "0.003"));
        String id = idOf(tailor(me, newJob()).andExpect(status().isCreated()));
        approve(me, id).andExpect(status().isOk());
        return new Approved(me, candidate, id);
    }

    /** A resume of the user's with the given structured content (null: nothing parsed yet); returns its id. */
    private UUID resume(UUID userId, boolean primary, String structured) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into resumes (id, user_id, label, file_key, file_type, size_bytes, is_primary, "
                + "parse_status, created_at, updated_at) values (?, ?, 'CV', ?, 'PDF', 1000, ?, ?, now(), now())", id,
                userId, "test/" + id, primary, structured == null ? "PENDING" : "PARSED");
        jdbc.update("insert into resume_versions (id, resume_id, version_number, structured, source, created_at, "
                + "updated_at) values (?, ?, 1, cast(? as jsonb), 'EDIT', now(), now())", UUID.randomUUID(), id,
                structured);
        return id;
    }

    private ResultActions renderDocument(Session session, String documentId, String json) throws Exception {
        return mvc.perform(post("/documents/" + documentId + "/render").header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions renderResume(Session session, UUID resumeId, String json) throws Exception {
        return mvc.perform(post("/resumes/" + resumeId + "/render").header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String field(ResultActions result, String path) throws Exception {
        return String.valueOf((Object) JsonPath.read(body(result), path));
    }

    private HttpResponse<byte[]> download(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private byte[] stored(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    private String keyOf(String renderId) {
        return jdbc.queryForObject("select storage_key from rendered_files where id = ?::uuid", String.class, renderId);
    }

    private long objectsUnder(String prefix) {
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()).keyCount();
    }

    private int rows(UUID userId) {
        return jdbc.queryForObject("select count(*) from rendered_files where user_id = ?", Integer.class, userId);
    }

    // ------------------------------------------------------------------------------------------ a document

    @Test
    void anApprovedDocumentRendersToAPdfAndThePreSignedLinkServesTheIdenticalBytes() throws Exception {
        Approved a = approvedDocument();

        ResultActions result = renderDocument(a.session(), a.id(), PDF).andExpect(status().isCreated())
                .andExpect(jsonPath("$.template").value("ATS")).andExpect(jsonPath("$.format").value("PDF"))
                .andExpect(jsonPath("$.pageSize").value("A4"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.cached").value(false));
        String url = field(result, "$.downloadUrl");
        String filename = field(result, "$.filename");

        // "Jordan abcd1234" at "Acme Test Co": a plain, safe name.
        assertThat(filename).isEqualTo("Jordan_" + a.candidate().name().substring(7) + "_Resume_Acme_Test_Co.pdf");
        HttpResponse<byte[]> response = download(url);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment")
                .contains("filename=\"" + filename + "\"");
        assertThat(response.body()).isEqualTo(stored(keyOf(field(result, "$.id"))));
        assertThat(RenderService.sha256(response.body())).isEqualTo(field(result, "$.sha256"));
        assertThat((long) response.body().length).isEqualTo(Long.parseLong(field(result, "$.sizeBytes")));
        // And it really is the resume: the name comes out first.
        assertThat(PdfRenderingTests.flat(PdfRenderingTests.text(response.body()))).startsWith(a.candidate().name());

        // The key: ours alone (user, document, template, page size, content hash, renderer version, extension).
        assertThat(keyOf(field(result, "$.id"))).matches(
                "renders/" + a.candidate().userId() + "/" + a.id() + "/ats-a4-[0-9a-f]{16}-r1\\.pdf");
    }

    @Test
    void theLinkIsShortLivedPerConfigAndEveryRequestGetsAFreshOneWithoutANewRender() throws Exception {
        Approved a = approvedDocument();
        Instant before = Instant.now();

        ResultActions first = renderDocument(a.session(), a.id(), PDF).andExpect(status().isCreated());
        ResultActions second = renderDocument(a.session(), a.id(), PDF).andExpect(status().isOk());

        // app.rendering.download-url-ttl defaults to five minutes; S3Mock does not enforce the expiry itself, so
        // what is checked is what a real store would be told: X-Amz-Expires, and the expiry the response states.
        for (ResultActions r : List.of(first, second)) {
            assertThat(field(r, "$.downloadUrl")).contains("X-Amz-Expires=300").contains("X-Amz-Signature=");
            assertThat(Instant.parse(field(r, "$.expiresAt"))).isBetween(before.plus(Duration.ofSeconds(295)),
                    Instant.now().plus(Duration.ofSeconds(301)));
        }
        assertThat(download(field(second, "$.downloadUrl")).statusCode()).isEqualTo(200);
    }

    @Test
    void aRepeatedRequestIsServedFromTheCacheAndRendersNothing() throws Exception {
        Approved a = approvedDocument();
        clearInvocations(renderer);

        String id = field(renderDocument(a.session(), a.id(), PDF).andExpect(status().isCreated()), "$.id");
        ResultActions again = renderDocument(a.session(), a.id(), PDF).andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true));
        renderDocument(a.session(), a.id(), "{}").andExpect(status().isOk()); // the defaults are this very variant

        assertThat(field(again, "$.id")).isEqualTo(id);
        verify(renderer, times(1)).render(any(), any(), any(), any());
        assertThat(rows(a.candidate().userId())).isEqualTo(1);
        assertThat(objectsUnder("renders/" + a.candidate().userId() + "/")).isEqualTo(1);
    }

    @Test
    void eachTemplateFormatAndPageSizeIsItsOwnCachedFile() throws Exception {
        Approved a = approvedDocument();
        clearInvocations(renderer);
        String[] variants = {
                "{\"template\":\"ATS\",\"format\":\"PDF\",\"pageSize\":\"A4\"}",
                "{\"template\":\"STYLED\",\"format\":\"PDF\",\"pageSize\":\"A4\"}",
                "{\"template\":\"ATS\",\"format\":\"DOCX\",\"pageSize\":\"A4\"}",
                "{\"template\":\"ATS\",\"format\":\"PDF\",\"pageSize\":\"LETTER\"}",
                "{\"template\":\"STYLED\",\"format\":\"DOCX\",\"pageSize\":\"LETTER\"}" };

        for (String variant : variants) {
            renderDocument(a.session(), a.id(), variant).andExpect(status().isCreated());
        }
        for (String variant : variants) {
            renderDocument(a.session(), a.id(), variant).andExpect(status().isOk());
        }

        verify(renderer, times(5)).render(any(), any(), any(), any());
        assertThat(rows(a.candidate().userId())).isEqualTo(5);
        assertThat(objectsUnder("renders/" + a.candidate().userId() + "/")).isEqualTo(5);
    }

    @Test
    void aDocxIsServedWithItsContentTypeAndOpensWithTheSameName() throws Exception {
        Approved a = approvedDocument();

        ResultActions result = renderDocument(a.session(), a.id(),
                "{\"template\":\"STYLED\",\"format\":\"DOCX\",\"pageSize\":\"LETTER\"}").andExpect(status().isCreated());

        assertThat(field(result, "$.filename")).endsWith("_Resume_Acme_Test_Co.docx");
        HttpResponse<byte[]> response = download(field(result, "$.downloadUrl"));
        assertThat(response.headers().firstValue("Content-Type")).hasValue(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(response.body()))) {
            assertThat(docx.getParagraphs().get(0).getStyleID()).isEqualTo("Title");
            assertThat(docx.getParagraphs().get(0).getText()).isEqualTo(a.candidate().name());
        }
    }

    @Test
    void concurrentIdenticalRequestsEndUpWithOneFileAndOneRow() throws Exception {
        Approved a = approvedDocument();
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = java.util.stream.IntStream.range(0, 4)
                    .<Callable<Integer>>mapToObj(i -> () -> renderDocument(a.session(), a.id(), PDF).andReturn()
                            .getResponse().getStatus())
                    .toList();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isIn(200, 201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(a.candidate().userId())).isEqualTo(1);
        assertThat(objectsUnder("renders/" + a.candidate().userId() + "/")).isEqualTo(1);
    }

    @Test
    void aFileThatWentMissingFromStorageIsRenderedAgain() throws Exception {
        Approved a = approvedDocument();
        String id = field(renderDocument(a.session(), a.id(), PDF).andExpect(status().isCreated()), "$.id");
        String key = keyOf(id);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        clearInvocations(renderer);

        ResultActions again = renderDocument(a.session(), a.id(), PDF).andExpect(status().isCreated());

        verify(renderer, times(1)).render(any(), any(), any(), any());
        assertThat(keyOf(field(again, "$.id"))).isEqualTo(key);
        assertThat(download(field(again, "$.downloadUrl")).statusCode()).isEqualTo(200);
        assertThat(rows(a.candidate().userId())).isEqualTo(1);
    }

    // -------------------------------------------------------------------------------------- who may render what

    @CoversEndpoints({"GET /documents/{id}/files", "GET /documents/{id}/files/{fileId}/download"})
    @Test
    void theFilesOfADocumentCanBeListedAndEachGetsAFreshLinkOnlyForItsOwner() throws Exception {
        Approved a = approvedDocument();
        ResultActions pdf = renderDocument(a.session(), a.id(), PDF).andExpect(status().isCreated());
        renderDocument(a.session(), a.id(), "{\"template\":\"STYLED\",\"format\":\"DOCX\",\"pageSize\":\"LETTER\"}")
                .andExpect(status().isCreated());
        String pdfId = field(pdf, "$.id");

        ResultActions list = mvc.perform(get("/documents/" + a.id() + "/files").header("Authorization", bearer(a.session())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(pdfId)).andExpect(jsonPath("$[0].format").value("PDF"))
                .andExpect(jsonPath("$[1].format").value("DOCX")).andExpect(jsonPath("$[0].downloadUrl").doesNotExist())
                .andExpect(jsonPath("$[0].filename").value(field(pdf, "$.filename")));
        assertThat(body(list)).doesNotContain("renders/");

        ResultActions link = mvc.perform(get("/documents/" + a.id() + "/files/" + pdfId + "/download")
                .header("Authorization", bearer(a.session()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pdfId));
        assertThat(field(link, "$.downloadUrl")).contains("X-Amz-Expires=300");
        HttpResponse<byte[]> response = download(field(link, "$.downloadUrl"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(RenderService.sha256(response.body())).isEqualTo(field(pdf, "$.sha256"));

        // Someone else: 404 for the list, the link, and for their own document id paired with this file.
        Session intruder = newSession();
        mvc.perform(get("/documents/" + a.id() + "/files").header("Authorization", bearer(intruder)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/documents/" + a.id() + "/files/" + pdfId + "/download")
                .header("Authorization", bearer(intruder))).andExpect(status().isNotFound());
        Approved b = approvedDocument();
        mvc.perform(get("/documents/" + b.id() + "/files/" + pdfId + "/download")
                .header("Authorization", bearer(b.session()))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("file_not_found"));
        mvc.perform(get("/documents/" + a.id() + "/files")).andExpect(status().isUnauthorized());
    }

    @CoversEndpoints({"POST /documents/{id}/render"})
    @Test
    void anotherUserGets404ForSomeoneElsesDocumentAndNothingIsStored() throws Exception {
        Approved a = approvedDocument();
        Session intruder = newSession();

        renderDocument(intruder, a.id(), PDF).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("document_not_found"));
        renderDocument(intruder, UUID.randomUUID().toString(), PDF).andExpect(status().isNotFound());

        assertThat(rows(userIdOf(intruder))).isZero();
        assertThat(objectsUnder("renders/" + userIdOf(intruder) + "/")).isZero();
        assertThat(rows(a.candidate().userId())).isZero();
    }

    @Test
    void aDraftCannotBeRenderedAndNeitherCanAFlaggedOne() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubFactCheck(candidate);
        stubTailor(candidate.userId(), tailorOk(UUID.randomUUID(), "0.003"));
        String draft = idOf(tailor(me, newJob()).andExpect(status().isCreated()));
        Session other = newSession();
        Candidate otherCandidate = seed(other);
        stubFactCheck(otherCandidate);
        stubTailor(otherCandidate.userId(), tailorInvented(UUID.randomUUID()));
        String flagged = idOf(tailor(other, newJob()).andExpect(status().isCreated()));
        assertThat(statusOf(flagged)).isEqualTo("FACT_CHECK_FAILED");

        renderDocument(me, draft, PDF).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_not_approved"));
        renderDocument(other, flagged, PDF).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_not_approved"));

        assertThat(rows(candidate.userId())).isZero();
        assertThat(objectsUnder("renders/" + candidate.userId() + "/")).isZero();
        // And someone else's draft is still a 404, not a 409 that would reveal it exists.
        renderDocument(other, draft, PDF).andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedRequestsAreRefused() throws Exception {
        mvc.perform(post("/documents/" + UUID.randomUUID() + "/render").contentType(MediaType.APPLICATION_JSON)
                .content(PDF)).andExpect(status().isUnauthorized());
        mvc.perform(post("/resumes/" + UUID.randomUUID() + "/render").contentType(MediaType.APPLICATION_JSON)
                .content(PDF)).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownTemplateFormatOrPageSizeIsABadRequest() throws Exception {
        Approved a = approvedDocument();
        for (String bad : List.of("{\"template\":\"FANCY\"}", "{\"format\":\"EXE\"}", "{\"pageSize\":\"A3\"}",
                "{\"template\":7}", "[]")) {
            renderDocument(a.session(), a.id(), bad).andExpect(status().isBadRequest());
        }
        assertThat(rows(a.candidate().userId())).isZero();
    }

    // ------------------------------------------------------------------------------------------ own resumes

    @Test
    void aUsersOwnResumeRendersWithoutTailoringAndKeepsTheYorubaLettersAndTheirMarks() throws Exception {
        Session me = newSession();
        UUID userId = userIdOf(me);
        UUID resumeId = resume(userId, true, RenderingFixtures.shortResume().toString());

        ResultActions result = renderResume(me, resumeId, "{\"template\":\"STYLED\",\"format\":\"PDF\"}")
                .andExpect(status().isCreated());

        assertThat(field(result, "$.filename")).isEqualTo("Sola_Ogunleye_Resume.pdf");
        byte[] pdf = download(field(result, "$.downloadUrl")).body();
        String text = PdfRenderingTests.flat(PdfRenderingTests.text(pdf));
        assertThat(text).startsWith(PdfRenderingTests.NAME);
        PdfRenderingTests.assertInOrder(text, "Summary", "Experience", "Senior Backend Engineer", "Education", "Skills");
        // Keyed by the resume version, not by any document.
        assertThat(keyOf(field(result, "$.id"))).startsWith("renders/" + userId + "/");
        assertThat(jdbc.queryForObject("select source_type from rendered_files where id = ?::uuid", String.class,
                field(result, "$.id"))).isEqualTo("RESUME_VERSION");
    }

    @Test
    void editingTheResumeInPlaceGivesNewContentANewFileAndLeavesTheOldOne() throws Exception {
        Session me = newSession();
        UUID resumeId = resume(userIdOf(me), true, RenderingFixtures.shortResume().toString());
        String oldId = field(renderResume(me, resumeId, "{}").andExpect(status().isCreated()), "$.id");
        renderResume(me, resumeId, "{}").andExpect(status().isOk());

        // The editor updates the latest version in place (ResumeContentService), so the version id stays the same.
        jdbc.update("update resume_versions set structured = jsonb_set(structured, '{headline}', '\"Edited headline\"') "
                + "where resume_id = ?", resumeId);
        ResultActions edited = renderResume(me, resumeId, "{}").andExpect(status().isCreated());

        assertThat(field(edited, "$.id")).isNotEqualTo(oldId);
        assertThat(PdfRenderingTests.flat(PdfRenderingTests.text(download(field(edited, "$.downloadUrl")).body())))
                .contains("Edited headline");
        assertThat(rows(userIdOf(me))).isEqualTo(2);
        assertThat(objectsUnder("renders/" + userIdOf(me) + "/")).isEqualTo(2);
    }

    @CoversEndpoints({"POST /resumes/{id}/render"})
    @Test
    void aResumeWithoutContentCannotBeRenderedAndSomeoneElsesIsA404() throws Exception {
        Session me = newSession();
        UUID empty = resume(userIdOf(me), true, null);
        UUID filled = resume(userIdOf(me), false, RenderingFixtures.shortResume().toString());
        Session intruder = newSession();

        renderResume(me, empty, PDF).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resume_content_required"));
        renderResume(intruder, filled, PDF).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resume_not_found"));
        renderResume(me, UUID.randomUUID(), PDF).andExpect(status().isNotFound());

        assertThat(rows(userIdOf(me))).isZero();
        assertThat(rows(userIdOf(intruder))).isZero();
    }

    // ---------------------------------------------------------------------------------------- account deletion

    @Test
    void deletingTheAccountRemovesItsRenderedFilesAndOnlyIts() throws Exception {
        Approved leaving = approvedDocument();
        Approved bystander = approvedDocument();
        UUID resumeId = resume(leaving.candidate().userId(), false, RenderingFixtures.shortResume().toString());
        renderDocument(leaving.session(), leaving.id(), PDF).andExpect(status().isCreated());
        renderDocument(leaving.session(), leaving.id(), "{\"format\":\"DOCX\"}").andExpect(status().isCreated());
        renderResume(leaving.session(), resumeId, PDF).andExpect(status().isCreated());
        renderDocument(bystander.session(), bystander.id(), PDF).andExpect(status().isCreated());
        // A rendered file that lost its row (say, a failed earlier delete) must go too.
        String orphan = "renders/" + leaving.candidate().userId() + "/" + UUID.randomUUID() + "/orphan.pdf";
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(orphan).build(), RequestBody.fromBytes(new byte[] { 1 }));
        assertThat(objectsUnder("renders/" + leaving.candidate().userId() + "/")).isEqualTo(4);

        mvc.perform(delete("/me").header("Authorization", "Bearer " + leaving.session().accessToken()))
                .andExpect(status().isNoContent());

        assertThat(rows(leaving.candidate().userId())).isZero();
        assertThat(objectsUnder("renders/" + leaving.candidate().userId() + "/")).isZero();
        assertThat(rows(bystander.candidate().userId())).isEqualTo(1);
        assertThat(objectsUnder("renders/" + bystander.candidate().userId() + "/")).isEqualTo(1);
    }
}
