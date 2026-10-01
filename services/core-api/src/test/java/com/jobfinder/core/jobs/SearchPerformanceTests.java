package com.jobfinder.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * The P2.6 performance bar: p95 under 300 ms on 20,000 active-ish jobs (PLAN.md section 11, docs/adr/0023-job-search.md).
 *
 * <p>Tagged {@code perf}, so the default {@code ./mvnw test} skips it (it loads 20k rows and a 1024-dimension HNSW
 * index, and a latency assertion has no place in the everyday suite). Run it with {@code make search-perf}, or
 * {@code ./mvnw test -Pperf -Dtest=SearchPerformanceTests}. It drives the real API (security filter chain, JSON
 * included) with a mix of queries against generated data and prints p50/p95/p99 per query type.
 *
 * <p>The data is synthetic but shaped like the real thing: Zipf-distributed words (a few in most descriptions, a long
 * tail of rare ones), about 2.5 KB descriptions, 600 companies, 30 cities, some salaries in several currencies and
 * periods, 10% expired jobs, posting dates over 60 days, and embeddings of the pinned 1024 dimensions grouped in 64
 * clusters (random vectors in 1024 dimensions are all almost equally far apart, which is a much harder, unlike-real
 * case for an HNSW index).
 */
@Tag("perf")
class SearchPerformanceTests extends JobsTestSupport {

    private static final int JOBS = 20_000;
    private static final double P95_LIMIT_MS = 300;
    private static final int WARMUP = 60;

    private static final String[] ROLES = { "Software Engineer", "Backend Engineer", "Frontend Engineer",
            "Full Stack Developer", "Data Engineer", "Data Scientist", "Machine Learning Engineer", "DevOps Engineer",
            "Site Reliability Engineer", "Platform Engineer", "Mobile Developer", "QA Engineer", "Security Engineer",
            "Product Manager", "Project Manager", "Product Designer", "UX Researcher", "Business Analyst",
            "Account Executive", "Customer Success Manager", "Sales Development Representative", "Marketing Manager",
            "Content Writer", "Financial Analyst", "Accountant", "HR Business Partner", "Recruiter",
            "Operations Manager", "Support Engineer", "Technical Writer", "Solutions Architect", "Cloud Architect",
            "Database Administrator", "Network Engineer", "Embedded Engineer", "Game Developer", "Data Analyst",
            "Engineering Manager", "Director of Engineering", "Legal Counsel" };
    private static final String[] LEVELS = { "Junior", "Mid-level", "Senior", "Lead", "Staff", "Principal", "" , "", "" };
    private static final String[] SENIORITY = { "JUNIOR", "MID", "SENIOR", "LEAD", "LEAD", "EXECUTIVE", null, null, null };
    private static final String[][] PLACES = { { "Lagos", "NG" }, { "Abuja", "NG" }, { "London", "GB" },
            { "Manchester", "GB" }, { "Berlin", "DE" }, { "Munich", "DE" }, { "Paris", "FR" }, { "Amsterdam", "NL" },
            { "Dublin", "IE" }, { "Madrid", "ES" }, { "Lisbon", "PT" }, { "Warsaw", "PL" }, { "Toronto", "CA" },
            { "Vancouver", "CA" }, { "New York", "US" }, { "San Francisco", "US" }, { "Austin", "US" },
            { "Seattle", "US" }, { "Chicago", "US" }, { "Boston", "US" }, { "Denver", "US" }, { "Nairobi", "KE" },
            { "Cape Town", "ZA" }, { "Accra", "GH" }, { "Cairo", "EG" }, { "Dubai", "AE" }, { "Bangalore", "IN" },
            { "Singapore", "SG" }, { "Sydney", "AU" }, { "Sao Paulo", "BR" }, { null, "US" }, { null, "GB" },
            { null, "NG" } };
    private static final String[] SKILLS = { "Java", "Python", "Go", "Rust", "TypeScript", "React", "Spring", "Kafka",
            "Kubernetes", "Docker", "AWS", "GCP", "Azure", "PostgreSQL", "MongoDB", "Redis", "GraphQL", "Terraform",
            "Linux", "SQL", "Spark", "Airflow", "Figma", "Salesforce", "Excel", "Swift", "Kotlin", "Node.js" };
    private static final String[] WORDS = ("team build product customer platform data service system design quality "
            + "deliver experience growth market process project support scale performance security cloud api user "
            + "feature release testing review code document plan strategy budget report analysis stakeholder partner "
            + "culture mentor learning remote flexible benefits equity salary vacation health insurance office "
            + "company mission values diverse inclusive collaboration communication ownership impact roadmap metrics "
            + "pipeline monitoring incident deployment architecture database network infrastructure automation "
            + "integration migration backlog sprint agile scrum cross functional background degree years experience "
            + "required preferred bonus nice responsibilities requirements qualifications apply interview process "
            + "hiring manager director lead senior junior intern contract permanent").split(" ");

    /** What one run of a query type measured. */
    private record Result(String type, List<Double> millis) {
        double percentile(double p) {
            List<Double> sorted = millis.stream().sorted().toList();
            int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }

    private final Map<String, List<Double>> timings = new LinkedHashMap<>();
    private Session me;
    private final Random random = new Random(20260901);

    @Test
    void searchStaysUnder300MillisecondsAtP95OnTwentyThousandJobs() throws Exception {
        long start = System.nanoTime();
        load();
        System.out.printf("[perf] loaded %d jobs in %.1fs%n", JOBS, (System.nanoTime() - start) / 1e9);
        me = newSession();
        seedUserState();
        List<UUID> embedded = jdbc.queryForList(
                "select id from jobs where status = 'ACTIVE' and embedding is not null order by id limit 400", UUID.class);
        assertThat(embedded).isNotEmpty();

        warmUp(embedded);
        timings.clear();

        measure("keyword, one common word", 150, i -> "/jobs?q=" + pick("engineer", "developer", "team", "manager",
                "senior", "data", "platform", "customer") + "&limit=20");
        measure("keyword, several words", 150, i -> "/jobs?q=" + pick("senior+backend+engineer", "python+aws+data",
                "customer+success+manager", "product+design+figma", "devops+kubernetes+terraform",
                "remote+machine+learning") + "&limit=20");
        measure("keyword, rare word", 100, i -> "/jobs?q=" + pick("terraform", "salesforce", "graphql", "swift",
                "airflow", "kotlin") + "&limit=20");
        measure("keyword, phrase and exclusion", 100, i -> "/jobs?q=" + pick("%22site+reliability%22",
                "engineer+-manager", "%22product+manager%22+-junior", "java+or+kotlin") + "&limit=20");
        measure("newest first, no keyword", 100, i -> "/jobs?limit=20");
        measure("filters only", 200, i -> "/jobs?" + pick("country=US&workMode=REMOTE", "country=GB&country=DE&seniority=SENIOR",
                "employmentType=CONTRACT&postedWithinDays=7", "location=london", "seniority=LEAD&workMode=HYBRID",
                "minSalary=100000&salaryCurrency=USD", "minSalary=50000&salaryCurrency=GBP&country=GB",
                "postedWithinDays=3&country=NG", "companyId=" + companyOf(i)) + "&limit=20");
        measure("keyword and filters", 200, i -> "/jobs?q=" + pick("engineer", "manager", "developer", "analyst")
                + "&" + pick("country=US&workMode=REMOTE", "seniority=SENIOR&employmentType=FULL_TIME",
                "country=GB&postedWithinDays=14", "minSalary=80000&salaryCurrency=USD&workMode=REMOTE",
                "country=DE&country=NL&seniority=MID") + "&limit=20");
        measure("similar jobs", 150, i -> "/jobs/" + embedded.get(i % embedded.size()) + "/similar?limit=20");
        measure("similar jobs with filters", 150, i -> "/jobs/" + embedded.get(i % embedded.size()) + "/similar?"
                + pick("workMode=REMOTE", "country=US&seniority=SENIOR", "employmentType=CONTRACT",
                "country=NG", "postedWithinDays=7") + "&limit=20");
        measureDeepPages("keyword, deep pages (cursor)", 12, 10, "engineer");
        measureDeepPages("newest first, deep pages (cursor)", 12, 10, null);
        measure("job detail", 150, i -> "/jobs/" + embedded.get(i % embedded.size()));
        measure("saved jobs", 50, i -> "/saved-jobs?limit=20");

        report();
        checkRecallOfFilteredSimilarJobs(embedded);

        List<Double> all = timings.values().stream().flatMap(List::stream).toList();
        Result overall = new Result("overall", all);
        for (var entry : timings.entrySet()) {
            Result result = new Result(entry.getKey(), entry.getValue());
            assertThat(result.percentile(95)).as("p95 of '%s' (ms)", entry.getKey()).isLessThan(P95_LIMIT_MS);
        }
        assertThat(overall.percentile(95)).as("overall p95 (ms)").isLessThan(P95_LIMIT_MS);
    }

    // --- the measuring ---

    private void measure(String type, int times, IntFunction<String> path) throws Exception {
        List<Double> millis = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            String url = path.apply(i);
            long start = System.nanoTime();
            ResultActions result = getAs(me, url);
            double elapsed = (System.nanoTime() - start) / 1e6;
            result.andExpect(status().isOk());
            millis.add(elapsed);
        }
        timings.put(type, millis);
    }

    /** Walks {@code pages} pages of a query by cursor, {@code walks} times, timing each page request separately. */
    private void measureDeepPages(String type, int walks, int pages, String keyword) throws Exception {
        List<Double> millis = new ArrayList<>();
        for (int walk = 0; walk < walks; walk++) {
            String base = "/jobs?limit=20" + (keyword == null ? "" : "&q=" + keyword)
                    + (walk % 2 == 0 ? "" : "&country=US");
            String url = base;
            for (int page = 0; page < pages; page++) {
                long start = System.nanoTime();
                ResultActions result = getAs(me, url);
                double elapsed = (System.nanoTime() - start) / 1e6;
                result.andExpect(status().isOk());
                millis.add(elapsed);
                String body = result.andReturn().getResponse().getContentAsString();
                Map<String, Object> json = JsonPath.read(body, "$");
                assertThat(json.get("nextCursor")).as("page %d of '%s' has a next page", page + 1, type).isNotNull();
                url = base + "&cursor=" + json.get("nextCursor");
            }
        }
        timings.put(type, millis);
    }

    private void warmUp(List<UUID> embedded) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            getAs(me, "/jobs?q=engineer&country=US&limit=20").andExpect(status().isOk());
            getAs(me, "/jobs?limit=20").andExpect(status().isOk());
            getAs(me, "/jobs/" + embedded.get(i % embedded.size()) + "/similar?limit=20").andExpect(status().isOk());
        }
    }

    private void report() throws IOException {
        StringBuilder table = new StringBuilder();
        table.append(String.format("%n[perf] %,d jobs, %s%n", JOBS, Instant.now()));
        table.append(String.format("%-40s %6s %9s %9s %9s %9s%n", "query type", "n", "p50 ms", "p95 ms", "p99 ms", "max ms"));
        List<Double> all = new ArrayList<>();
        for (var entry : timings.entrySet()) {
            Result r = new Result(entry.getKey(), entry.getValue());
            all.addAll(entry.getValue());
            table.append(String.format("%-40s %6d %9.1f %9.1f %9.1f %9.1f%n", r.type(), r.millis().size(),
                    r.percentile(50), r.percentile(95), r.percentile(99), r.percentile(100)));
        }
        Result overall = new Result("ALL (the mix above)", all);
        table.append(String.format("%-40s %6d %9.1f %9.1f %9.1f %9.1f%n", overall.type(), all.size(),
                overall.percentile(50), overall.percentile(95), overall.percentile(99), overall.percentile(100)));
        System.out.println(table);
        Path out = Path.of("target", "search-perf.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table.toString());
    }

    /**
     * A selective filter must not starve "similar jobs": even when the nearest vectors are the wrong kind, the
     * filtered query still returns a full page (pgvector iterative scan, or an exact plan), where plain HNSW would
     * return only what its candidate list happened to contain.
     */
    private void checkRecallOfFilteredSimilarJobs(List<UUID> embedded) throws Exception {
        int fullPages = 0;
        int asked = 0;
        for (int i = 0; i < 60; i++) {
            UUID source = embedded.get(i * 3 % embedded.size());
            String body = getAs(me, "/jobs/" + source + "/similar?country=NG&workMode=REMOTE&limit=20")
                    .andReturn().getResponse().getContentAsString();
            int returned = ((List<?>) JsonPath.read(body, "$.items")).size();
            int available = jdbc.queryForObject("""
                    select count(*) from jobs where status = 'ACTIVE' and country = 'NG' and work_mode = 'REMOTE'
                       and embedding is not null and id <> ?
                    """, Integer.class, source);
            asked++;
            if (returned >= Math.min(20, available)) {
                fullPages++;
            }
        }
        System.out.printf("[perf] filtered similar jobs returned all that exist (up to a page) for %d of %d sources%n",
                fullPages, asked);
        assertThat(fullPages).as("filtered similar-jobs queries that found everything available").isEqualTo(asked);
    }

    // --- the data ---

    private String pick(String... options) {
        return options[random.nextInt(options.length)];
    }

    private UUID companyOf(int i) {
        return jdbc.queryForObject("select company_id from jobs where status = 'ACTIVE' "
                + "order by id offset ? limit 1", UUID.class, (i * 37) % JOBS);
    }

    private void seedUserState() throws Exception {
        UUID user = userIdOf(me);
        jdbc.update("""
                insert into user_job_actions (user_id, job_id, action, created_at)
                select ?, id, 'HIDDEN', now() from jobs where status = 'ACTIVE' order by id limit 1000
                """, user);
        jdbc.update("""
                insert into user_job_actions (user_id, job_id, action, created_at)
                select ?, id, 'SAVED', now() - (random() * interval '30 days') from jobs
                 where status = 'ACTIVE' and id not in (select job_id from user_job_actions where user_id = ?)
                 order by id desc limit 300
                """, user, user);
    }

    private void load() {
        Random rnd = new Random(42);
        List<UUID> companies = new ArrayList<>();
        List<Object[]> companyRows = new ArrayList<>();
        String[] first = { "Blue", "Red", "North", "Bright", "Iron", "Open", "Swift", "Prime", "Nova", "Delta", "Apex",
                "Cloud", "Pixel", "Green", "Solid" };
        String[] second = { "Labs", "Systems", "Works", "Pay", "Health", "Logistics", "Media", "Cloud", "Analytics",
                "Robotics", "Bank", "Foods", "Energy", "Learning", "Mobility", "Software", "Security", "Retail", "Travel",
                "Studio" };
        for (int i = 0; i < 600; i++) {
            UUID id = new UUID(rnd.nextLong(), rnd.nextLong());
            String name = first[rnd.nextInt(first.length)] + " " + second[rnd.nextInt(second.length)] + " " + i;
            companies.add(id);
            companyRows.add(new Object[] { id, name, name.toLowerCase() });
        }
        jdbc.batchUpdate("insert into companies (id, name, normalized_name, created_at, updated_at) "
                + "values (?, ?, ?, now(), now())", companyRows);

        String[] vocabulary = vocabulary(rnd);
        String insert = """
                insert into jobs (id, company_id, title, normalized_title, description_text, location_raw, city, country,
                                  work_mode, employment_type, seniority, salary_min, salary_max, salary_currency,
                                  salary_period, apply_url, posted_at, status, fingerprint, skills, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < JOBS; i++) {
            UUID id = new UUID(rnd.nextLong(), rnd.nextLong());
            String role = ROLES[rnd.nextInt(ROLES.length)];
            int level = rnd.nextInt(LEVELS.length);
            String title = (LEVELS[level].isEmpty() ? "" : LEVELS[level] + " ") + role;
            String[] place = PLACES[rnd.nextInt(PLACES.length)];
            String mode = pickWeighted(rnd, new String[] { "REMOTE", "HYBRID", "ONSITE", null }, new int[] { 30, 20, 45, 5 });
            String type = pickWeighted(rnd, new String[] { "FULL_TIME", "CONTRACT", "PART_TIME", "INTERNSHIP", null },
                    new int[] { 78, 10, 5, 2, 5 });
            Instant posted = now.minusSeconds((long) (rnd.nextDouble() * 60 * 86400));
            boolean datePosted = rnd.nextInt(10) < 6;
            String[] skills = new String[2 + rnd.nextInt(4)];
            for (int s = 0; s < skills.length; s++) {
                skills[s] = SKILLS[rnd.nextInt(SKILLS.length)];
            }
            Object[] salary = salary(rnd, place[1]);
            batch.add(new Object[] { id, companies.get(rnd.nextInt(companies.size())), title, title.toLowerCase(),
                    description(rnd, vocabulary, title, skills), place[0] == null ? "Remote - " + place[1] : place[0] + ", " + place[1],
                    place[0], place[1], mode, type, SENIORITY[level], salary[0], salary[1], salary[2], salary[3],
                    "https://jobs.example.test/" + i, datePosted ? at(posted) : null,
                    rnd.nextInt(10) == 0 ? "EXPIRED" : "ACTIVE", "perf-" + id, skills, at(posted), at(posted) });
            if (batch.size() == 1000) {
                jdbc.batchUpdate(insert, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(insert, batch);
        }
        loadEmbeddingsAndIndex();
        jdbc.execute("analyze jobs");
        jdbc.execute("analyze companies");
        jdbc.execute("analyze user_job_actions");
    }

    /** Clustered 1024-dimension vectors, then the HNSW index (rebuilt after the load, as a backfill would leave it). */
    private void loadEmbeddingsAndIndex() {
        String indexDef = jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'jobs_embedding_hnsw_idx'",
                String.class);
        jdbc.execute("drop index jobs_embedding_hnsw_idx");
        jdbc.execute("drop table if exists perf_centers");
        jdbc.execute("create table perf_centers (id int primary key, v vector(1024) not null)");
        jdbc.execute("""
                insert into perf_centers
                select c, (select array_agg((random() * 2 - 1)::float4) from generate_series(1, 1024) g where g > c * 0)::vector(1024)
                  from generate_series(0, 63) c
                """);
        jdbc.execute("""
                update jobs j set embedding = (c.v + (select array_agg(((random() - 0.5) * 1.2)::float4)
                                                        from generate_series(1, 1024) g where g > length(j.title) * 0 - 1)::vector(1024)),
                                  embedding_model = 'voyage-4', embedding_input_hash = repeat('b', 64), embedded_at = now()
                  from perf_centers c
                 where c.id = abs(hashtext(j.id::text)) % 64
                """);
        jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                // A parallel build shares memory through /dev/shm, which a container gives only 64 MB of.
                statement.execute("set max_parallel_maintenance_workers = 0");
                statement.execute("set maintenance_work_mem = '768MB'");
                statement.execute(indexDef);
                statement.execute("reset maintenance_work_mem");
                statement.execute("reset max_parallel_maintenance_workers");
            }
            return null;
        });
        jdbc.execute("drop table perf_centers");
    }

    private static String pickWeighted(Random rnd, String[] values, int[] weights) {
        int total = Arrays.stream(weights).sum();
        int roll = rnd.nextInt(total);
        for (int i = 0; i < values.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return values[i];
            }
        }
        return values[values.length - 1];
    }

    private static Object[] salary(Random rnd, String country) {
        if (rnd.nextInt(100) >= 40) {
            return new Object[] { null, null, null, null };
        }
        String currency = switch (country) {
            case "US" -> "USD";
            case "GB" -> "GBP";
            case "CA" -> "CAD";
            case "DE", "FR", "NL", "IE", "ES", "PT" -> "EUR";
            case "NG" -> "NGN";
            default -> "USD";
        };
        int kind = rnd.nextInt(10);
        if (kind < 7) {
            int min = 30_000 + rnd.nextInt(120_000);
            return new Object[] { min, min + rnd.nextInt(40_000), currency, "YEAR" };
        }
        if (kind < 9) {
            int min = 3_000 + rnd.nextInt(10_000);
            return new Object[] { min, min + rnd.nextInt(3_000), currency, "MONTH" };
        }
        int min = 15 + rnd.nextInt(80);
        return new Object[] { min, min + rnd.nextInt(20), currency, "HOUR" };
    }

    /** Real words first (so common queries match many jobs), then pseudo-words for a long tail of rare terms. */
    private static String[] vocabulary(Random rnd) {
        List<String> words = new ArrayList<>(Arrays.asList(WORDS));
        for (String skill : SKILLS) {
            words.add(skill.toLowerCase());
        }
        String[] syllables = { "ba", "ko", "ri", "mu", "te", "sa", "lo", "vi", "ne", "do", "xa", "qui", "fro", "gle" };
        while (words.size() < 1500) {
            StringBuilder word = new StringBuilder();
            for (int s = 0, n = 2 + rnd.nextInt(3); s < n; s++) {
                word.append(syllables[rnd.nextInt(syllables.length)]);
            }
            words.add(word.toString());
        }
        return words.toArray(String[]::new);
    }

    private static String description(Random rnd, String[] vocabulary, String title, String[] skills) {
        StringBuilder text = new StringBuilder("About the role\n").append("We are hiring a ").append(title).append(". ");
        for (int sentence = 0; sentence < 22; sentence++) {
            if (sentence == 8) {
                text.append("\n\nWhat you will do\n");
            }
            if (sentence == 15) {
                text.append("\n\nRequirements\n- ").append(String.join("\n- ", skills)).append("\n");
            }
            for (int w = 0, n = 10 + rnd.nextInt(8); w < n; w++) {
                int index = (int) (vocabulary.length * Math.pow(rnd.nextDouble(), 2.6));
                text.append(vocabulary[index]).append(' ');
            }
            text.append(". ");
        }
        return text.toString();
    }
}
