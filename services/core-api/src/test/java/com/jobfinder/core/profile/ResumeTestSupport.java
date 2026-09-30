package com.jobfinder.core.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.identity.AuthTestSupport;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Shared plumbing for the resume tests: real object storage (S3Mock) plus upload helpers. */
public abstract class ResumeTestSupport extends AuthTestSupport {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    protected S3Client s3;

    @Value("${app.storage.bucket}")
    protected String bucket;

    protected static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    protected static byte[] docx() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : new String[] { "[Content_Types].xml", "_rels/.rels", "word/document.xml" }) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    protected Session newSession() throws Exception {
        return login(registerVerifiedUser(), PASSWORD, newIp());
    }

    protected UUID userIdOf(String accessToken) {
        String subject = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(subject, "$.sub"));
    }

    protected ResultActions upload(Session session, String filename, String declaredType, byte[] content)
            throws Exception {
        return mvc.perform(uploadRequest(session, filename, declaredType, content));
    }

    protected MockMultipartHttpServletRequestBuilder uploadRequest(Session session, String filename,
            String declaredType, byte[] content) {
        MockMultipartHttpServletRequestBuilder request = multipart("/resumes")
                .file(new MockMultipartFile("file", filename, declaredType, content));
        request.header("Authorization", "Bearer " + session.accessToken());
        return request;
    }

    /** Uploads a valid PDF and returns the new resume's ID. */
    protected UUID uploadPdf(Session session) throws Exception {
        String body = upload(session, "cv.pdf", "application/pdf", pdf()).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    protected String fileKey(UUID resumeId) {
        return jdbc.queryForObject("select file_key from resumes where id = ?", String.class, resumeId);
    }

    protected boolean objectExists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    protected long objectsUnder(String prefix) {
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()).keyCount();
    }

    protected void putObject(String key) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(pdf()));
    }

    protected HttpResponse<byte[]> httpGet(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
