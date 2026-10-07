package com.jobfinder.core.matching;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.embeddings.internal.EmbeddingFixtures;
import com.jobfinder.core.jobs.JobsTestSupport;

import tools.jackson.databind.json.JsonMapper;

/**
 * Plumbing for the matching tests: candidates written straight into the tables (user, primary resume, a version with
 * content and a current embedding, preferences), jobs from {@link JobsTestSupport}, and ai-service stubbed per user so
 * tests that share the WireMock server never see each other's requests.
 */
public abstract class MatchingTestSupport extends JobsTestSupport {

    public static final String PATH = "/v1/score-matches";
    public static final String PROMPT = "match_scoring/v1";

    @Autowired
    protected WireMockServer aiService;

    @Autowired
    protected JsonMapper mapper;

    /** A candidate that was seeded: the user, the primary resume and its latest version. */
    public record Seeded(UUID userId, UUID resumeId, UUID versionId, String structured) {
    }

    public static String resumeJson(String headline, String... skills) {
        List<String> quoted = new ArrayList<>();
        for (String skill : skills) {
            quoted.add("\"" + skill + "\"");
        }
        return """
                {"schema_version":1,
                 "contact":{"full_name":"Test Person","email":"test.person@example.test","phone":"+1 555 0100"},
                 "headline":"%s","summary":"Builds backend services.",
                 "experience":[{"company":"Northwind","title":"Senior Backend Engineer","start_date":"2021-03",
                                "end_date":null,"is_current":true,"bullets":["Built services in Java."]}],
                 "education":[{"institution":"Example University","degree":"BSc","field_of_study":"Computer Science"}],
                 "skills":[%s],"projects":[],"certifications":[]}
                """.formatted(headline, String.join(",", quoted));
    }

    protected UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, role, status, ai_consent_version, ai_consent_at, created_at, "
                + "updated_at) values (?, ?, 'USER', 'ACTIVE', 'test', now(), now(), now())", id, "match-" + id + "@example.test");
        return id;
    }

    /** A user who has signed in just now (what the nightly run counts as active). */
    protected void signedInDaysAgo(UUID userId, int days) {
        jdbc.update("insert into refresh_tokens (id, user_id, token_hash, family_id, expires_at, created_at, "
                + "updated_at) values (?, ?, ?, ?, now() + interval '30 days', ?, ?)", UUID.randomUUID(), userId,
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID(), at(Instant.now().minus(days, ChronoUnit.DAYS)),
                at(Instant.now().minus(days, ChronoUnit.DAYS)));
    }

    /** A user with a primary resume whose first version has this content and a current vector at {@code degrees}. */
    protected Seeded seedCandidate(double degrees, String headline, String... skills) {
        return seedFor(newUser(), degrees, headline, skills);
    }

    protected Seeded seedFor(UUID userId, double degrees, String headline, String... skills) {
        UUID resumeId = UUID.randomUUID();
        jdbc.update("insert into resumes (id, user_id, label, file_key, file_type, size_bytes, is_primary, "
                + "parse_status, created_at, updated_at) values (?, ?, 'CV', ?, 'PDF', 1000, true, 'PARSED', now(), "
                + "now())", resumeId, userId, "test/" + resumeId);
        String structured = resumeJson(headline, skills);
        UUID versionId = addVersion(resumeId, 1, structured, degrees);
        return new Seeded(userId, resumeId, versionId, structured);
    }

    protected UUID addVersion(UUID resumeId, int number, String structured, double degrees) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("insert into resume_versions (id, resume_id, version_number, structured, source, created_at, "
                + "updated_at) values (?, ?, ?, cast(? as jsonb), 'EDIT', now(), now())", versionId, resumeId, number,
                structured);
        embedResume(versionId, structured, degrees);
        return versionId;
    }

    /** Stores a vector for the version under the hash of its current content (a current embedding). */
    protected void embedResume(UUID versionId, String structured, double degrees) {
        jdbc.update("""
                update resume_versions set embedding = cast(? as vector), embedding_model = 'voyage-4',
                       embedding_input_hash = ?, embedded_at = now() where id = ?
                """, literal(unit(degrees)), EmbeddingFixtures.resumeInputHash(structured), versionId);
    }

    protected void savePreferences(UUID userId, String workModes, String locations, Integer minSalary, String currency,
            String excludedCompanies) {
        jdbc.update("""
                insert into preferences (id, user_id, target_titles, locations, work_modes, min_salary, currency,
                                         needs_sponsorship, excluded_companies, excluded_industries, created_at,
                                         updated_at)
                values (?, ?, '{}', cast(? as text[]), cast(? as text[]), ?, ?, false, cast(? as text[]), '{}', now(),
                        now())
                """, UUID.randomUUID(), userId, array(locations), array(workModes), minSalary, currency,
                array(excludedCompanies));
    }

    protected void saveEmptyPreferences(UUID userId) {
        savePreferences(userId, "", "", null, null, "");
    }

    private static String array(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return "{}";
        }
        StringBuilder out = new StringBuilder("{");
        String[] parts = commaSeparated.split(",");
        for (int i = 0; i < parts.length; i++) {
            out.append(i > 0 ? "," : "").append('"').append(parts[i].strip()).append('"');
        }
        return out.append('}').toString();
    }

    /** A recently posted, embedded job (cosine to a candidate at 0 degrees is cos(degrees)). */
    protected UUID job(String title, double degrees, String... skills) {
        return insert(spec().title(title).skills(skills).embedding(unit(degrees))
                .postedAt(Instant.now().minus(1, ChronoUnit.HOURS)));
    }

    // --- ai-service stubs, scoped to one user ---

    private MappingBuilder forUser(UUID userId) {
        return post(urlPathEqualTo(PATH)).withRequestBody(matchingJsonPath("$.user_id", equalTo(userId.toString())));
    }

    /** ai-service answers every job in {@code scores} with its score (any request for a subset is served). */
    protected void stubScores(UUID userId, Map<UUID, Integer> scores) {
        stubScores(userId, scores, "0.002");
    }

    protected void stubScores(UUID userId, Map<UUID, Integer> scores, String costUsd) {
        aiService.stubFor(forUser(userId).willReturn(okJson(scoresBody(userId, scores, Map.of(), costUsd))));
    }

    /** As {@link #stubScores} but the model failed for the jobs in {@code failed} (error code per job). */
    protected void stubScoresWithFailures(UUID userId, Map<UUID, Integer> scores, Map<UUID, String> failed) {
        aiService.stubFor(forUser(userId).willReturn(okJson(scoresBody(userId, scores, failed, "0.002"))));
    }

    protected void stubStatus(UUID userId, int status, String body) {
        aiService.stubFor(forUser(userId).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/problem+json").withBody(body)));
    }

    protected String scoresBody(UUID userId, Map<UUID, Integer> scores, Map<UUID, String> failed, String costUsd) {
        List<Map<String, Object>> results = new ArrayList<>();
        scores.forEach((id, score) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("job_id", id.toString());
            r.put("status", "scored");
            r.put("score", score);
            r.put("strengths", List.of("Strong overlap with " + id.toString().substring(0, 4)));
            r.put("gaps", List.of("No gap noted"));
            r.put("error_code", null);
            results.add(r);
        });
        failed.forEach((id, code) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("job_id", id.toString());
            r.put("status", "failed");
            r.put("score", null);
            r.put("strengths", List.of());
            r.put("gaps", List.of());
            r.put("error_code", code);
            results.add(r);
        });
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("user_id", userId.toString());
        usage.put("feature", "match_scoring");
        usage.put("provider", "test");
        usage.put("model", "test-model");
        usage.put("input_tokens", 1000);
        usage.put("output_tokens", 200);
        usage.put("cost_usd", costUsd);
        usage.put("latency_ms", 12);
        usage.put("prompt_version", PROMPT);
        // One call id per body, so a repeated request would be recorded once: the stub is used by few requests.
        usage.put("call_id", UUID.randomUUID().toString());
        usage.put("pricing_version", "test");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt_version", PROMPT);
        body.put("model", "test-model");
        body.put("results", results);
        body.put("usage", List.of(usage));
        return mapper.writeValueAsString(body);
    }

    protected List<LoggedRequest> scoreRequests(UUID userId) {
        return aiService.findAll(postRequestedFor(urlPathEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.user_id", equalTo(userId.toString()))));
    }

    protected int scoreRequestCount(UUID userId) {
        return scoreRequests(userId).size();
    }

    /** The job ids that one recorded request asked about. */
    protected static List<String> askedJobs(LoggedRequest request) {
        return JsonPath.read(request.getBodyAsString(), "$.jobs[*].id");
    }

    protected int storedScores(UUID userId) {
        return jdbc.queryForObject("select count(*) from match_scores where user_id = ?", Integer.class, userId);
    }
}
