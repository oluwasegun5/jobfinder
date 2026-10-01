package com.jobfinder.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Keyword search and filters: what matches, in which order, and what is turned away. */
class JobSearchTests extends JobsTestSupport {

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    @Test
    void searchNeedsASignedInUser() throws Exception {
        mvc.perform(get("/jobs?q=engineer")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/jobs/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/saved-jobs")).andExpect(status().isUnauthorized());
    }

    // --- keyword ---

    @Test
    void aKeywordMatchesTitleCompanyAndDescriptionAndStemsWords() throws Exception {
        insert(spec().title("Platform Engineer").company("Initech").description("Run the cloud."));
        insert(spec().title("Designer").company("Quasar Labs").description("Make things pretty."));
        insert(spec().title("Writer").company("Initech").description("Write about quasars and tools."));
        Session me = newSession();

        // Title (and its plural), company name, and a stemmed description word.
        assertThat(titles(getAs(me, "/jobs?q=engineers"))).containsExactly("Platform Engineer");
        assertThat(titles(getAs(me, "/jobs?q=quasar"))).containsExactlyInAnyOrder("Designer", "Writer");
        assertThat(titles(getAs(me, "/jobs?q=initech+cloud"))).containsExactly("Platform Engineer");
        assertThat(titles(getAs(me, "/jobs?q=nothingmatchesthis"))).isEmpty();
    }

    @Test
    void aTitleMatchOutranksACompanyMatchWhichOutranksADescriptionMatch() throws Exception {
        insert(spec().title("Designer").company("Other").description("We use rustacean tools"));
        insert(spec().title("Designer").company("Rustacean Works").description("Pixels."));
        insert(spec().title("Rustacean Designer").company("Other").description("Pixels."));
        Session me = newSession();

        ResultActionsHolder ranked = new ResultActionsHolder(getAs(me, "/jobs?q=rustacean"));

        assertThat(ranked.companies()).containsExactly("Other", "Rustacean Works", "Other");
        assertThat(ranked.titles()).containsExactly("Rustacean Designer", "Designer", "Designer");
    }

    @Test
    void ofEqualRelevanceTheNewerJobComesFirst() throws Exception {
        insert(spec().title("Data Engineer (old)").company("Same Co").description("same text").postedAt(daysAgo(90)));
        insert(spec().title("Data Engineer (new)").company("Same Co").description("same text").postedAt(daysAgo(1)));
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs?q=data+engineer"))).containsExactly("Data Engineer (new)", "Data Engineer (old)");
    }

    @Test
    void recencyOnlyBreaksTiesRelevanceStillWins() throws Exception {
        insert(spec().title("Kotlin Developer").postedAt(daysAgo(300)));
        insert(spec().title("Developer").description("Some kotlin on the side").postedAt(daysAgo(0)));
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs?q=kotlin"))).containsExactly("Kotlin Developer", "Developer");
    }

    @Test
    void aQueryThatIsOnlyStopWordsFindsNothingInsteadOfFailing() throws Exception {
        insert("Engineer");
        Session me = newSession();

        getAs(me, "/jobs?q=the+and+of").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void searchSyntaxIsTreatedAsTextNotAsATsqueryExpression() throws Exception {
        insert("C++ Engineer");
        insert("Java Engineer");
        Session me = newSession();

        getAs(me, "/jobs?q=" + java.net.URLEncoder.encode("engineer & | ! ( ) :* '\"", java.nio.charset.StandardCharsets.UTF_8))
                .andExpect(status().isOk());
        assertThat(titles(getAs(me, "/jobs?q=engineer+-java"))).containsExactly("C++ Engineer");
    }

    // --- listing without a keyword ---

    @Test
    void withoutAKeywordTheNewestJobsComeFirstAndOnlyActiveOnesAreListed() throws Exception {
        insert(spec().title("Old").postedAt(daysAgo(10)));
        insert(spec().title("Newest").postedAt(daysAgo(1)));
        insert(spec().title("Middle").postedAt(daysAgo(5)));
        insert(spec().title("Gone").postedAt(daysAgo(0)).status("EXPIRED"));
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs"))).containsExactly("Newest", "Middle", "Old");
        assertThat(titles(getAs(me, "/jobs?q=gone"))).isEmpty();
    }

    @Test
    void aPostingDatedInTheFutureDoesNotJumpTheQueue() throws Exception {
        insert(spec().title("Honest").postedAt(daysAgo(1)));
        UUID liar = insert(spec().title("Liar").postedAt(daysAgo(30)));
        jdbc.update("update jobs set posted_at = now() + interval '30 days' where id = ?", liar);
        insert(spec().title("Fresh").postedAt(Instant.now()));
        Session me = newSession();

        // Its first-seen time caps its date: it ranks with the jobs first seen when it was.
        assertThat(titles(getAs(me, "/jobs"))).containsExactly("Fresh", "Honest", "Liar");
    }

    // --- filters ---

    @Test
    void workModeEmploymentTypeSeniorityAndCountryFiltersTakeAnyOfTheirValuesAndAllFiltersApply() throws Exception {
        insert(spec().title("A").workMode("REMOTE").employmentType("FULL_TIME").seniority("SENIOR").country("US"));
        insert(spec().title("B").workMode("HYBRID").employmentType("CONTRACT").seniority("MID").country("GB"));
        insert(spec().title("C").workMode("ONSITE").employmentType("FULL_TIME").seniority("SENIOR").country("NG"));
        insert(spec().title("D").country("US"));
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs?workMode=REMOTE&workMode=HYBRID"))).containsExactlyInAnyOrder("A", "B");
        assertThat(titles(getAs(me, "/jobs?employmentType=FULL_TIME"))).containsExactlyInAnyOrder("A", "C");
        assertThat(titles(getAs(me, "/jobs?seniority=MID&seniority=SENIOR"))).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(titles(getAs(me, "/jobs?country=us&country=NG"))).containsExactlyInAnyOrder("A", "C", "D");
        assertThat(titles(getAs(me, "/jobs?country=US&workMode=REMOTE"))).containsExactly("A");
        assertThat(titles(getAs(me, "/jobs?employmentType=FULL_TIME&seniority=SENIOR&country=NG"))).containsExactly("C");
    }

    @Test
    void theLocationFilterMatchesACityIgnoringCase() throws Exception {
        insert(spec().title("Here").city("Lagos").country("NG"));
        insert(spec().title("There").city("Berlin").country("DE"));
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs?location=LAGOS"))).containsExactly("Here");
        assertThat(titles(getAs(me, "/jobs?location=Paris"))).isEmpty();
    }

    @Test
    void theCompanyFilterListsOneEmployersJobs() throws Exception {
        UUID acme = company("Acme");
        insert(spec().title("Mine").company("Acme"));
        insert(spec().title("Theirs").company("Globex"));
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs?companyId=" + acme))).containsExactly("Mine");
    }

    @Test
    void theSalaryFilterComparesYearlyAmountsInOneCurrencyOnly() throws Exception {
        insert(spec().title("Yearly USD").salary("50000", "80000", "USD", "YEAR"));
        insert(spec().title("Hourly USD").salary("40", null, "USD", "HOUR"));          // 83,200 a year
        insert(spec().title("Monthly GBP").salary("6000", "7000", "GBP", "MONTH"));    // 84,000 a year, pounds
        insert(spec().title("Low USD").salary("30000", "40000", "USD", "YEAR"));
        insert(spec().title("No period").salary("200000", null, "USD", null));          // not guessed: left out
        insert(spec().title("No salary"));
        Session me = newSession();

        // "At least 70k": a range counts when its top reaches it; dollars are never compared with pounds.
        assertThat(titles(getAs(me, "/jobs?minSalary=70000&salaryCurrency=USD")))
                .containsExactlyInAnyOrder("Yearly USD", "Hourly USD");
        assertThat(titles(getAs(me, "/jobs?minSalary=70000&salaryCurrency=gbp"))).containsExactly("Monthly GBP");
        assertThat(titles(getAs(me, "/jobs?minSalary=85000&salaryCurrency=USD"))).isEmpty();
        // A currency alone: jobs that state their salary in it.
        assertThat(titles(getAs(me, "/jobs?salaryCurrency=GBP"))).containsExactly("Monthly GBP");
    }

    @Test
    void postedWithinKeepsOnlyRecentJobs() throws Exception {
        insert(spec().title("Today").postedAt(daysAgo(0)));
        insert(spec().title("Last week").postedAt(daysAgo(6)));
        insert(spec().title("Last month").postedAt(daysAgo(20)));
        insert(spec().title("Ancient").postedAt(daysAgo(200)));
        Session me = newSession();

        assertThat(titles(getAs(me, "/jobs?postedWithinDays=1"))).containsExactly("Today");
        assertThat(titles(getAs(me, "/jobs?postedWithinDays=7"))).containsExactly("Today", "Last week");
        assertThat(titles(getAs(me, "/jobs?postedWithinDays=30"))).containsExactly("Today", "Last week", "Last month");
    }

    @Test
    void keywordAndFiltersCombine() throws Exception {
        insert(spec().title("Backend Engineer").workMode("REMOTE").country("US").seniority("SENIOR"));
        insert(spec().title("Backend Engineer").workMode("ONSITE").country("US").seniority("SENIOR"));
        insert(spec().title("Frontend Engineer").workMode("REMOTE").country("US").seniority("SENIOR"));
        Session me = newSession();

        getAs(me, "/jobs?q=backend&workMode=REMOTE&country=US&seniority=SENIOR")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Backend Engineer"))
                .andExpect(jsonPath("$.items[0].workMode").value("REMOTE"));
    }

    // --- the shape of a result ---

    @Test
    void aResultCarriesWhatTheListNeedsAndNoNulls() throws Exception {
        UUID id = insert(spec().title("Backend Engineer").company("Acme").city("Lagos").country("NG")
                .workMode("REMOTE").employmentType("FULL_TIME").seniority("SENIOR")
                .salary("50000.00", "80000", "USD", "YEAR").postedAt(daysAgo(2))
                .description("Build   and run\n\nservices that scale."));
        Session me = newSession();

        getAs(me, "/jobs").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].company.name").value("Acme"))
                .andExpect(jsonPath("$.items[0].location").value("Lagos"))
                .andExpect(jsonPath("$.items[0].country").value("NG"))
                .andExpect(jsonPath("$.items[0].salary.min").value(50000))
                .andExpect(jsonPath("$.items[0].salary.max").value(80000))
                .andExpect(jsonPath("$.items[0].salary.currency").value("USD"))
                .andExpect(jsonPath("$.items[0].salary.period").value("YEAR"))
                .andExpect(jsonPath("$.items[0].summary").value("Build and run services that scale."))
                .andExpect(jsonPath("$.items[0].saved").value(false))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))));
    }

    // --- validation ---

    @Test
    void badInputIsAnswered400WithAProblemDetail() throws Exception {
        Session me = newSession();

        for (String bad : List.of("limit=51", "limit=0", "limit=abc", "workMode=NOPE", "country=USA",
                "postedWithinDays=0", "postedWithinDays=366", "minSalary=-5&salaryCurrency=USD", "salaryCurrency=DOLLARS",
                "q=" + "x".repeat(201), "companyId=not-a-uuid")) {
            getAs(me, "/jobs?" + bad).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        getAs(me, "/jobs?minSalary=1000").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("salary_currency_required"));
        getAs(me, "/jobs?limit=50").andExpect(status().isOk());
    }

    @Test
    void theLimitBoundsThePage() throws Exception {
        for (int i = 0; i < 5; i++) {
            insert("Job " + i);
        }
        Session me = newSession();

        getAs(me, "/jobs?limit=2").andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").exists());
        getAs(me, "/jobs").andExpect(jsonPath("$.items.length()").value(5));
    }

    // --- the search document ---

    @Test
    void theSearchDocumentFollowsTitleCompanyAndDescriptionButNotEmbeddingWrites() throws Exception {
        UUID id = insert(spec().title("Gardener").company("Greenery").description("Prune hedges"));
        String before = jdbc.queryForObject("select search::text from jobs where id = ?", String.class, id);
        assertThat(before).contains("'garden':1A").contains("'greeneri':2B").contains("'prune':3");
        String head = jdbc.queryForObject("select search_head::text from jobs where id = ?", String.class, id);
        assertThat(head).isEqualTo("'garden':1A 'greeneri':2B");

        embed(id, unit(10));
        jdbc.update("update jobs set updated_at = now() where id = ?", id);
        assertThat(jdbc.queryForObject("select search::text from jobs where id = ?", String.class, id))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select search_head::text from jobs where id = ?", String.class, id))
                .isEqualTo(head);

        jdbc.update("update jobs set title = 'Florist' where id = ?", id);
        assertThat(jdbc.queryForObject("select search::text from jobs where id = ?", String.class, id))
                .contains("'florist':1A").doesNotContain("'garden'");

        jdbc.update("update companies set name = 'Meadow Co' where name = 'Greenery'");
        assertThat(jdbc.queryForObject("select search::text from jobs where id = ?", String.class, id))
                .contains("'meadow'").doesNotContain("'greeneri'");
        assertThat(jdbc.queryForObject("select search_head::text from jobs where id = ?", String.class, id))
                .contains("'florist':1A").contains("'meadow':2B").doesNotContain("greeneri");
    }

    @Test
    void theSearchIndexOnlyCoversActiveJobsAndTheSortKeyIsStored() throws Exception {
        UUID id = insert(spec().title("Mine").postedAt(daysAgo(3)));

        assertThat(jdbc.queryForObject("select sort_at is not null and salary_annual_top is null from jobs where id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'jobs_search_gin_idx'",
                String.class)).contains("USING gin").contains("status");
    }

    /** Reads titles and company names of one response without parsing it twice. */
    private static final class ResultActionsHolder {
        private final String body;

        ResultActionsHolder(org.springframework.test.web.servlet.ResultActions result) throws Exception {
            this.body = result.andReturn().getResponse().getContentAsString();
        }

        List<String> titles() {
            return com.jayway.jsonpath.JsonPath.read(body, "$.items[*].title");
        }

        List<String> companies() {
            return com.jayway.jsonpath.JsonPath.read(body, "$.items[*].company.name");
        }
    }
}
