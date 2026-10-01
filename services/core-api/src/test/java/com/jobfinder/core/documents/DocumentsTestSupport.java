package com.jobfinder.core.documents;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.jobs.JobsTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plumbing for the documents tests: a candidate whose primary resume is the fictional resume that ai-service's own
 * tests use (a unique contact name per candidate keeps WireMock stubs of different tests apart), a job, and ai-service
 * stubbed with the contract files its tests pin: a faithful tailoring, a tailoring with an invented employer, and a
 * fact check that passes or flags the invented employer. No test talks to a model.
 */
public abstract class DocumentsTestSupport extends JobsTestSupport {

    public static final String TAILOR_PATH = "/v1/tailor-resume";
    public static final String FACT_CHECK_PATH = "/v1/fact-check";
    public static final String INVENTED = "FakeCorp";
    public static final String PASS = "{\"passed\":true,\"blocking\":0,\"warnings\":0,\"flags\":[],"
            + "\"checker_version\":\"fact_check/v1\"}";

    @Autowired
    protected WireMockServer aiService;

    @Autowired
    protected JsonMapper mapper;

    /** A candidate: the user, the name on the resume (unique, how stubs find them) and the resume's JSON. */
    public record Candidate(UUID userId, UUID versionId, String name, JsonNode source) {
    }

    protected String resource(String path) {
        try (InputStream in = DocumentsTestSupport.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected ObjectNode fixture(String path) {
        return (ObjectNode) mapper.readTree(resource(path));
    }

    /** The signed-in user gets a parsed primary resume (the shared fictional one, under a unique name). */
    protected Candidate seed(Session session) {
        UUID userId = userIdOf(session);
        String name = "Jordan " + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode source = fixture("/documents/source-resume.json");
        ((ObjectNode) source.get("contact")).put("full_name", name);
        UUID resumeId = UUID.randomUUID();
        jdbc.update("insert into resumes (id, user_id, label, file_key, file_type, size_bytes, is_primary, "
                + "parse_status, created_at, updated_at) values (?, ?, 'CV', ?, 'PDF', 1000, true, 'PARSED', now(), "
                + "now())", resumeId, userId, "test/" + resumeId);
        UUID versionId = UUID.randomUUID();
        jdbc.update("insert into resume_versions (id, resume_id, version_number, structured, source, created_at, "
                + "updated_at) values (?, ?, 1, cast(? as jsonb), 'EDIT', now(), now())", versionId, resumeId,
                mapper.writeValueAsString(source));
        return new Candidate(userId, versionId, name, source);
    }

    protected UUID newJob() {
        return insert(spec().title("Staff Backend Engineer").company("Acme Test Co")
                .description("We need a backend engineer who knows Java, Spring Boot and Kafka.")
                .postedAt(Instant.now().minus(1, ChronoUnit.HOURS)));
    }

    // --- ai-service: tailoring ---

    /** The success body ai-service's contract test pins, with this call id and cost. */
    protected ObjectNode tailorOk(UUID callId, String costUsd) {
        ObjectNode body = fixture("/ai-service/tailor-resume-ok.json");
        ObjectNode usage = (ObjectNode) body.get("usage").get(0);
        usage.put("call_id", callId.toString());
        usage.put("cost_usd", costUsd);
        return body;
    }

    /** The same answer, but the model added an employer that is not in the resume (flagged BLOCKING). */
    protected ObjectNode tailorInvented(UUID callId) {
        ObjectNode body = tailorOk(callId, "0.004");
        ObjectNode entry = mapper.createObjectNode();
        entry.put("company", INVENTED);
        entry.put("title", "Principal Engineer");
        entry.put("start_date", "2019-01");
        entry.putNull("end_date");
        entry.put("is_current", false);
        entry.putArray("bullets").add("Ran the platform at " + INVENTED + ".");
        ((ArrayNode) body.get("resume").get("experience")).add(entry);
        ObjectNode change = mapper.createObjectNode();
        change.put("id", "c5");
        change.put("section", "EXPERIENCE");
        change.put("op", "ADD");
        change.put("path", "experience[2]");
        change.putNull("before");
        change.set("after", entry);
        change.put("rationale", "Adds relevant experience.");
        ((ArrayNode) body.get("changes")).add(change);
        ObjectNode flag = mapper.createObjectNode();
        flag.put("code", "NEW_EMPLOYER");
        flag.put("severity", "BLOCKING");
        flag.put("path", "experience[2].company");
        flag.put("value", INVENTED);
        flag.put("message", "This employer is not in your resume.");
        ObjectNode check = (ObjectNode) body.get("fact_check");
        ArrayNode flags = mapper.createArrayNode();
        flags.add(flag);
        flags.addAll((ArrayNode) check.get("flags"));
        check.set("flags", flags);
        check.put("passed", false);
        check.put("blocking", 1);
        return body;
    }

    protected void stubTailor(UUID userId, JsonNode body) {
        aiService.stubFor(tailorFor(userId).willReturn(okJson(mapper.writeValueAsString(body))));
    }

    protected void stubTailor(UUID userId, int status, String body) {
        aiService.stubFor(tailorFor(userId).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/problem+json").withBody(body)));
    }

    protected void stubSlowTailor(UUID userId, JsonNode body, int delayMillis) {
        aiService.stubFor(tailorFor(userId)
                .willReturn(okJson(mapper.writeValueAsString(body)).withFixedDelay(delayMillis)));
    }

    private MappingBuilder tailorFor(UUID userId) {
        return post(urlPathEqualTo(TAILOR_PATH))
                .withRequestBody(matchingJsonPath("$.user_id", equalTo(userId.toString())));
    }

    protected List<LoggedRequest> tailorRequests(UUID userId) {
        return aiService.findAll(postRequestedFor(urlPathEqualTo(TAILOR_PATH))
                .withRequestBody(matchingJsonPath("$.user_id", equalTo(userId.toString()))));
    }

    // --- ai-service: fact check (keyed on the unique name in the source) ---

    private MappingBuilder factCheckFor(Candidate c) {
        return post(urlPathEqualTo(FACT_CHECK_PATH))
                .withRequestBody(matchingJsonPath("$.source.contact.full_name", equalTo(c.name())));
    }

    /** The fact check passes anything, and flags an invented employer (FakeCorp) in the candidate as BLOCKING. */
    protected void stubFactCheck(Candidate c) {
        aiService.stubFor(factCheckFor(c).atPriority(10).willReturn(okJson(PASS)));
        aiService.stubFor(factCheckFor(c).atPriority(5)
                .withRequestBody(matchingJsonPath("$.candidate.experience[?(@.company == '" + INVENTED + "')]"))
                .willReturn(okJson(blockingEmployer("experience[0].company"))));
    }

    protected static String blockingEmployer(String path) {
        return "{\"passed\":false,\"blocking\":1,\"warnings\":0,\"flags\":[{\"code\":\"NEW_EMPLOYER\","
                + "\"severity\":\"BLOCKING\",\"path\":\"" + path + "\",\"value\":\"" + INVENTED
                + "\",\"message\":\"This employer is not in your resume.\"}],\"checker_version\":\"fact_check/v1\"}";
    }

    /** Every fact check for this candidate answers with {@code body} (beats the default stubs). */
    protected void stubFactCheckAlways(Candidate c, String body) {
        aiService.stubFor(factCheckFor(c).atPriority(1).willReturn(okJson(body)));
    }

    protected void stubFactCheckDown(Candidate c) {
        aiService.stubFor(factCheckFor(c).atPriority(1).willReturn(aResponse().withStatus(503)));
    }

    protected int factCheckRequests(Candidate c) {
        return aiService.findAll(postRequestedFor(urlPathEqualTo(FACT_CHECK_PATH))
                .withRequestBody(matchingJsonPath("$.source.contact.full_name", equalTo(c.name())))).size();
    }

    // --- requests ---

    protected ResultActions tailor(Session session, UUID jobId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/jobs/" + jobId + "/tailor")
                .header("Authorization", bearer(session)));
    }

    protected ResultActions tailor(Session session, UUID jobId, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/jobs/" + jobId + "/tailor")
                .header("Authorization", bearer(session)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions patchAs(Session session, String id, String json) throws Exception {
        return mvc.perform(patch("/documents/" + id).header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions approve(Session session, String id) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/documents/" + id + "/approve")
                .header("Authorization", bearer(session)));
    }

    protected ResultActions deleteDocument(Session session, String id) throws Exception {
        return mvc.perform(delete("/documents/" + id).header("Authorization", bearer(session)));
    }

    protected static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.id");
    }

    protected static String reject(String changeId, int version) {
        return "{\"version\":" + version + ",\"operations\":[{\"op\":\"SET_STATE\",\"changeId\":\"" + changeId
                + "\",\"state\":\"REJECTED\"}]}";
    }

    protected String statusOf(String documentId) {
        return jdbc.queryForObject("select status from generated_documents where id = ?::uuid", String.class,
                documentId);
    }

    protected int documents(UUID userId) {
        return jdbc.queryForObject("select count(*) from generated_documents where user_id = ?", Integer.class,
                userId);
    }
}
