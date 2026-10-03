package com.jobfinder.core.jobs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.identity.AuthTestSupport;

/**
 * Shared plumbing for the job search tests: real Postgres with pgvector, the full security filter chain, jobs
 * inserted straight into the tables (the way ingestion leaves them), and signed-in users.
 */
public abstract class JobsTestSupport extends AuthTestSupport {

    public static final int DIMENSION = 1024;

    /** A job to insert. Everything has a plausible default; tests set only what they are about. */
    public static final class JobSpec {
        String title = "Software Engineer";
        String company = "Acme";
        String description = "Build and run services.";
        String locationRaw;
        String city;
        String country;
        String workMode;
        String employmentType;
        String seniority;
        BigDecimal salaryMin;
        BigDecimal salaryMax;
        String salaryCurrency;
        String salaryPeriod;
        Instant postedAt;
        Instant createdAt;
        String status = "ACTIVE";
        String applyUrl = "https://jobs.example.test/apply";
        String[] skills = {};
        double[] embedding;

        public JobSpec title(String v) { title = v; return this; }
        public JobSpec company(String v) { company = v; return this; }
        public JobSpec description(String v) { description = v; return this; }
        public JobSpec city(String v) { city = v; locationRaw = v; return this; }
        public JobSpec country(String v) { country = v; return this; }
        public JobSpec workMode(String v) { workMode = v; return this; }
        public JobSpec employmentType(String v) { employmentType = v; return this; }
        public JobSpec seniority(String v) { seniority = v; return this; }
        public JobSpec salary(String min, String max, String currency, String period) {
            salaryMin = min == null ? null : new BigDecimal(min);
            salaryMax = max == null ? null : new BigDecimal(max);
            salaryCurrency = currency;
            salaryPeriod = period;
            return this;
        }
        /** Posted this long ago, and first seen then. */
        public JobSpec postedAt(Instant v) { postedAt = v; createdAt = v; return this; }
        public JobSpec status(String v) { status = v; return this; }
        public JobSpec applyUrl(String v) { applyUrl = v; return this; }
        public JobSpec skills(String... v) { skills = v; return this; }
        public JobSpec embedding(double[] v) { embedding = v; return this; }
    }

    @BeforeEach
    protected void emptyJobs() {
        // Other test classes share this database and clean their own jobs when they start; start from nothing.
        jdbc.update("delete from jobs");
    }

    protected static JobSpec spec() {
        return new JobSpec();
    }

    protected UUID company(String name) {
        List<UUID> existing = jdbc.queryForList("select id from companies where name = ?", UUID.class, name);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into companies (id, name, normalized_name, created_at, updated_at) "
                + "values (?, ?, ?, now(), now())", id, name, name.toLowerCase() + "-" + id);
        return id;
    }

    protected UUID insert(JobSpec s) {
        UUID id = UUID.randomUUID();
        Instant created = s.createdAt != null ? s.createdAt : Instant.now();
        jdbc.update("""
                insert into jobs (id, company_id, title, normalized_title, description_html, description_text,
                                  location_raw, city, country, work_mode, employment_type, seniority, salary_min,
                                  salary_max, salary_currency, salary_period, apply_url, posted_at, status,
                                  fingerprint, skills, created_at, updated_at)
                values (?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, company(s.company), s.title, s.title.toLowerCase(), s.description, s.locationRaw, s.city,
                s.country, s.workMode, s.employmentType, s.seniority, s.salaryMin, s.salaryMax, s.salaryCurrency,
                s.salaryPeriod, s.applyUrl, s.postedAt == null ? null : at(s.postedAt), s.status,
                "test-" + id, s.skills, at(created), at(created));
        if (s.embedding != null) {
            embed(id, s.embedding);
        }
        return id;
    }

    protected UUID insert(String title) {
        return insert(spec().title(title));
    }

    protected static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    protected void embed(UUID jobId, double[] vector) {
        jdbc.update("""
                update jobs set embedding = cast(? as vector), embedding_model = 'voyage-4',
                       embedding_input_hash = repeat('a', 64), embedded_at = now() where id = ?
                """, literal(vector), jobId);
    }

    /** A vector of the pinned dimension at {@code degrees} in the plane of the first two axes: cos(a - b) apart. */
    protected static double[] unit(double degrees) {
        double[] v = new double[DIMENSION];
        v[0] = Math.cos(Math.toRadians(degrees));
        v[1] = Math.sin(Math.toRadians(degrees));
        return v;
    }

    protected static String literal(double[] v) {
        return Arrays.stream(v).mapToObj(Double::toString).collect(Collectors.joining(",", "[", "]"));
    }

    // --- users and requests ---

    protected Session newSession() throws Exception {
        return login(registerVerifiedUser(), PASSWORD, newIp());
    }

    protected UUID userIdOf(Session session) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(session.accessToken().split("\\.")[1]),
                StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(payload, "$.sub"));
    }

    protected static String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    protected ResultActions getAs(Session session, String path) throws Exception {
        // A browser (and Tomcat) read "+" in a query string as a space; MockMvc does not, so spell it out.
        return mvc.perform(get(java.net.URI.create(path.replace("+", "%20"))).header("Authorization", bearer(session)));
    }

    protected ResultActions putAs(Session session, String path) throws Exception {
        return mvc.perform(put(path).header("Authorization", bearer(session)));
    }

    protected ResultActions deleteAs(Session session, String path) throws Exception {
        return mvc.perform(delete(path).header("Authorization", bearer(session)));
    }

    /** The titles of a page, in order. */
    protected static List<String> titles(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.items[*].title");
    }

    protected static List<String> ids(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.items[*].id");
    }

    protected static String nextCursor(ResultActions result) throws Exception {
        return JsonPath.<String>read(result.andReturn().getResponse().getContentAsString(), "$.nextCursor");
    }

    protected static boolean hasNext(ResultActions result) throws Exception {
        java.util.Map<String, Object> body = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$");
        return body.get("nextCursor") != null;
    }

    /** Every page of {@code path} (which may already carry a query string), following the cursor to the end. */
    protected List<String> allIds(Session session, String path, int maxPages) throws Exception {
        List<String> all = new ArrayList<>();
        String separator = path.contains("?") ? "&" : "?";
        String url = path;
        for (int page = 0; page < maxPages; page++) {
            ResultActions result = getAs(session, url);
            all.addAll(ids(result));
            if (!hasNext(result)) {
                return all;
            }
            url = path + separator + "cursor=" + nextCursor(result);
        }
        throw new AssertionError("still more pages after " + maxPages);
    }
}
