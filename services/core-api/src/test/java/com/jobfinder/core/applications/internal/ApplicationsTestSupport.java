package com.jobfinder.core.applications.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.TestcontainersConfiguration.Mailpit;
import com.jobfinder.core.documents.WritingTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plumbing for the application tracker tests: requests, jobs, approved documents and packs written straight into the
 * tables, ai-service stubbed with the contract file its own tests pin (a faithful follow-up and one that invents an
 * employer), and Mailpit's HTTP API to read what a reminder really sent over SMTP. No test talks to a model.
 */
abstract class ApplicationsTestSupport extends WritingTestSupport {

    static final String FOLLOW_UP_PATH = "/v1/follow-up-email";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    protected Mailpit mailpit;

    /** An email as Mailpit holds it. */
    record Mail(String subject, String text, String html) {
    }

    // --- requests ---

    protected ResultActions create(Session session, String json) throws Exception {
        return postAs(session, "/applications", json);
    }

    /** An application entered by hand; returns its id. */
    protected String manual(Session session, String title) throws Exception {
        return idOf(create(session, "{\"title\":\"" + title + "\",\"company\":\"Acme\"}"));
    }

    protected String fromJob(Session session, UUID job) throws Exception {
        return idOf(create(session, "{\"jobId\":\"" + job + "\"}"));
    }

    protected ResultActions moveTo(Session session, String id, String status) throws Exception {
        return postAs(session, "/applications/" + id + "/status", "{\"status\":\"" + status + "\"}");
    }

    protected ResultActions patchApp(Session session, String id, String json) throws Exception {
        return mvc.perform(patch("/applications/" + id).header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions addReminder(Session session, String id, Instant dueAt, String extra) throws Exception {
        return postAs(session, "/applications/" + id + "/reminders",
                "{\"dueAt\":\"" + dueAt + "\"" + (extra == null ? "" : "," + extra) + "}");
    }

    protected ResultActions draft(Session session, String id, String json) throws Exception {
        return postAs(session, "/applications/" + id + "/follow-up-draft", json);
    }

    // --- rows ---

    protected int rows(String table, UUID userId) {
        return count("select count(*) from " + table + " where user_id = ?", userId);
    }

    protected String emailOf(UUID userId) {
        return jdbc.queryForObject("select email from users where id = ?", String.class, userId);
    }

    protected String reminderState(String id) {
        return jdbc.queryForObject("select state from reminders where id = ?::uuid", String.class, id);
    }

    /** Makes the reminder due in the past, as time passing would (the API only takes future times). */
    protected void makeDue(String reminderId) {
        jdbc.update("update reminders set due_at = now() - interval '1 minute' where id = ?::uuid", reminderId);
    }

    /** An APPROVED document of the type, for the job, as approval would leave it. */
    protected UUID approvedDocument(Candidate c, String type, UUID jobId) {
        UUID id = UUID.randomUUID();
        insertDocument(id, c, type, "APPROVED", jobId);
        return id;
    }

    protected UUID draftDocument(Candidate c, String type, UUID jobId) {
        UUID id = UUID.randomUUID();
        insertDocument(id, c, type, "DRAFT", jobId);
        return id;
    }

    private void insertDocument(UUID id, Candidate c, String type, String status, UUID jobId) {
        jdbc.update("""
                insert into generated_documents (id, user_id, type, status, job_id, job_title, base_resume_version_id,
                        prompt_version, source_content, content, changes, fact_check, version, created_at, updated_at,
                        approved_at)
                values (?, ?, ?, ?, ?, 'Staff Backend Engineer', ?, 'test/v1', '{}'::jsonb, '{}'::jsonb, '[]'::jsonb,
                        '{"passed":true,"blocking":0,"warnings":0,"flags":[],"checker_version":"t"}'::jsonb, 1, now(),
                        now(), case when ? = 'APPROVED' then now() end)
                """, id, c.userId(), type, status, jobId, c.versionId(), status);
    }

    protected UUID packFor(Candidate c, UUID jobId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into application_packs (id, user_id, job_id, job_title, base_resume_version_id, status, options,
                        parts, version, created_at, updated_at)
                values (?, ?, ?, 'Staff Backend Engineer', ?, 'COMPLETE', '{}'::jsonb, '{}'::jsonb, 1, now(), now())
                """, id, c.userId(), jobId, c.versionId());
        return id;
    }

    // --- ai-service: follow-up ---

    protected ObjectNode followUpOk(UUID callId, String costUsd) {
        ObjectNode body = fixture("/ai-service/follow-up-email-ok.json");
        usage(body, callId, costUsd);
        return body;
    }

    /** The pinned answer whose body claims an employer the resume does not show (BLOCKING). */
    protected ObjectNode followUpBlocked(UUID callId) {
        ObjectNode body = fixture("/ai-service/follow-up-email-blocked.json");
        usage(body, callId, "0.002");
        return body;
    }

    private static void usage(ObjectNode body, UUID callId, String costUsd) {
        ObjectNode usage = (ObjectNode) body.get("usage").get(0);
        usage.put("call_id", callId.toString());
        usage.put("cost_usd", costUsd);
    }

    protected void stubFollowUp(UUID userId, JsonNode body) {
        aiService.stubFor(followUpFor(userId).willReturn(okJson(mapper.writeValueAsString(body))));
    }

    protected void stubFollowUp(UUID userId, int status, String body) {
        aiService.stubFor(followUpFor(userId).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/problem+json").withBody(body)));
    }

    private MappingBuilder followUpFor(UUID userId) {
        return post(urlPathEqualTo(FOLLOW_UP_PATH))
                .withRequestBody(matchingJsonPath("$.user_id", equalTo(userId.toString())));
    }

    protected List<LoggedRequest> followUpRequests(UUID userId) {
        return aiService.findAll(postRequestedFor(urlPathEqualTo(FOLLOW_UP_PATH))
                .withRequestBody(matchingJsonPath("$.user_id", equalTo(userId.toString()))));
    }

    // --- Mailpit ---

    protected List<Mail> mails(String to) throws Exception {
        String query = URLEncoder.encode("to:" + to, StandardCharsets.UTF_8);
        String search = fetch("/api/v1/search?query=" + query);
        List<String> ids = JsonPath.read(search, "$.messages[*].ID");
        List<Mail> found = new ArrayList<>();
        for (String id : ids) {
            String body = fetch("/api/v1/message/" + id);
            found.add(new Mail(JsonPath.read(body, "$.Subject"), JsonPath.read(body, "$.Text"),
                    JsonPath.read(body, "$.HTML")));
        }
        return found;
    }

    protected List<Mail> awaitMail(String to, int count) {
        return Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150))
                .until(() -> mails(to), found -> found.size() >= count);
    }

    private String fetch(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(mailpit.apiBaseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
