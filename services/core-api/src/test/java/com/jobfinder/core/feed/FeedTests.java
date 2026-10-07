package com.jobfinder.core.feed;

import com.jobfinder.core.CoversEndpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.matching.MatchingTestSupport;

/**
 * GET /feed against real Postgres with ai-service stubbed: the done-when of P3.3 (hiding a job removes it and demotes
 * similar ones), boosts, caps, undo, per-user isolation, stable paging, empty states and what the feed costs.
 */
class FeedTests extends MatchingTestSupport {

    /** A feed fixture: one candidate whose model scores are stubbed, and the jobs by name. */
    private final class World {
        final Session session;
        final UUID user;
        final Map<String, UUID> jobs = new LinkedHashMap<>();
        final Map<UUID, Integer> scores = new LinkedHashMap<>();

        World(Session session) {
            this.session = session;
            this.user = userIdOf(session);
            seedFor(user, 0, "Backend engineer", "java");
            saveEmptyPreferences(user);
        }

        World add(String key, String title, String company, int score) {
            UUID id = insert(spec().title(title).company(company).skills("java").embedding(unit(5 + jobs.size()))
                    .postedAt(Instant.now().minus(1, ChronoUnit.HOURS)));
            jobs.put(key, id);
            scores.put(id, score);
            return this;
        }

        World stub() {
            stubScores(user, scores);
            return this;
        }

        UUID id(String key) {
            return jobs.get(key);
        }

        String titleOf(String key) {
            return jdbc.queryForObject("select title from jobs where id = ?", String.class, id(key));
        }
    }

    private World world() throws Exception {
        return new World(newSession());
    }

    private String feed(Session s) throws Exception {
        return getAs(s, "/feed").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** The cursor of the next page, or null on the last page (the field is omitted then). */
    private static String cursorOf(String body) {
        Map<String, Object> page = JsonPath.read(body, "$");
        return (String) page.get("nextCursor");
    }

    private static List<String> titlesOf(String body) {
        return JsonPath.read(body, "$.items[*].job.title");
    }

    private static double adjustmentOf(String body, String title) {
        List<Number> found = JsonPath.read(body, "$.items[?(@.job.title == '" + title + "')].adjustment");
        return found.get(0).doubleValue();
    }

    private static double feedScoreOf(String body, String title) {
        List<Number> found = JsonPath.read(body, "$.items[?(@.job.title == '" + title + "')].feedScore");
        return found.get(0).doubleValue();
    }

    private static List<String> codes(String body, String title) {
        List<Object> raw = JsonPath.read(body, "$.items[?(@.job.title == '" + title + "')].reasons[*].code");
        return raw.stream().map(Object::toString).toList();
    }

    private World hidingWorld() throws Exception {
        return world()
                .add("hidden", "Java Backend Engineer", "Globex", 85)
                .add("similar", "Java Backend Developer", "Initech", 84)
                .add("sameCompany", "Office Manager", "Globex", 83)
                .add("unrelated", "Data Analyst", "Umbrella", 82)
                .stub();
    }

    // --- the done-when ---

    @Test
    void hidingAJobRemovesItAndDemotesSimilarOnesButNotUnrelatedOnes() throws Exception {
        World w = hidingWorld();
        String before = feed(w.session);
        assertThat(titlesOf(before)).containsExactly("Java Backend Engineer", "Java Backend Developer", "Office Manager",
                "Data Analyst");
        assertThat(adjustmentOf(before, "Data Analyst")).isZero();

        putAs(w.session, "/jobs/" + w.id("hidden") + "/hide").andExpect(status().isNoContent());
        String after = feed(w.session);

        // removed from the feed and from search
        assertThat(titlesOf(after)).doesNotContain("Java Backend Engineer");
        assertThat(titles(getAs(w.session, "/jobs?q=java+backend"))).doesNotContain("Java Backend Engineer");
        // the similar title and the same company are demoted below the unrelated job that used to rank under them
        assertThat(titlesOf(after)).containsExactly("Data Analyst", "Java Backend Developer", "Office Manager");
        assertThat(adjustmentOf(after, "Java Backend Developer")).isCloseTo(-15, within(0.05));
        assertThat(codes(after, "Java Backend Developer")).containsExactly("HIDDEN_SIMILAR_TITLE");
        assertThat(feedScoreOf(after, "Java Backend Developer")).isCloseTo(69, within(0.05));
        assertThat(adjustmentOf(after, "Office Manager")).isCloseTo(-15, within(0.05));
        assertThat(codes(after, "Office Manager")).containsExactly("HIDDEN_SAME_COMPANY");
        // the unrelated job is untouched
        assertThat(adjustmentOf(after, "Data Analyst")).isZero();
        assertThat(feedScoreOf(after, "Data Analyst")).isEqualTo(82.0);
        assertThat(codes(after, "Data Analyst")).isEmpty();
        // the match score itself is not rewritten: the model's 84 is still shown
        assertThat((List<Integer>) JsonPath.read(after, "$.items[?(@.job.title == 'Java Backend Developer')].matchScore"))
                .containsExactly(84);
    }

    @Test
    void undoingTheHideRestoresTheFeed() throws Exception {
        World w = hidingWorld();
        String before = feed(w.session);
        putAs(w.session, "/jobs/" + w.id("hidden") + "/hide").andExpect(status().isNoContent());
        assertThat(titlesOf(feed(w.session))).doesNotContain("Java Backend Engineer");

        deleteAs(w.session, "/jobs/" + w.id("hidden") + "/hide").andExpect(status().isNoContent());
        String restored = feed(w.session);

        assertThat(titlesOf(restored)).isEqualTo(titlesOf(before));
        assertThat(adjustmentOf(restored, "Java Backend Developer")).isZero();
        assertThat(adjustmentOf(restored, "Office Manager")).isZero();
    }

    // --- boosts from saved and applied ---

    private World boostWorld() throws Exception {
        return world()
                .add("seed", "Python Data Engineer", "Acme", 70)
                .add("lookalike", "Python Data Developer", "Contoso", 61)
                .add("other", "Gardener", "Fabrikam", 65)
                .stub();
    }

    @Test
    void savingAJobBoostsSimilarOnesAndUnsavingTakesItBack() throws Exception {
        World w = boostWorld();
        assertThat(titlesOf(feed(w.session))).containsExactly("Python Data Engineer", "Gardener", "Python Data Developer");

        putAs(w.session, "/jobs/" + w.id("seed") + "/save").andExpect(status().isNoContent());
        String saved = feed(w.session);

        assertThat(titlesOf(saved)).containsExactly("Python Data Engineer", "Python Data Developer", "Gardener");
        assertThat(adjustmentOf(saved, "Python Data Developer")).isCloseTo(6, within(0.05));
        assertThat(codes(saved, "Python Data Developer")).containsExactly("SAVED_SIMILAR_TITLE");
        assertThat(adjustmentOf(saved, "Gardener")).isZero();
        assertThat(adjustmentOf(saved, "Python Data Engineer")).isZero(); // a saved job does not boost itself
        assertThat((List<Boolean>) JsonPath.read(saved, "$.items[?(@.job.title == 'Python Data Engineer')].job.saved"))
                .containsExactly(true);

        deleteAs(w.session, "/jobs/" + w.id("seed") + "/save").andExpect(status().isNoContent());
        assertThat(titlesOf(feed(w.session))).containsExactly("Python Data Engineer", "Gardener", "Python Data Developer");
    }

    @Test
    void anAppliedJobLeavesTheFeedStaysInSearchAndBoostsSimilarOnes() throws Exception {
        World w = boostWorld();

        putAs(w.session, "/jobs/" + w.id("seed") + "/applied").andExpect(status().isNoContent());
        String applied = feed(w.session);

        assertThat(titlesOf(applied)).containsExactly("Python Data Developer", "Gardener");
        assertThat(adjustmentOf(applied, "Python Data Developer")).isCloseTo(5, within(0.05));
        assertThat(codes(applied, "Python Data Developer")).containsExactly("APPLIED_SIMILAR_TITLE");
        getAs(w.session, "/jobs?q=python+engineer").andExpect(jsonPath("$.items[?(@.title == 'Python Data Engineer')].applied")
                .value(true));
        getAs(w.session, "/jobs/" + w.id("seed")).andExpect(jsonPath("$.applied").value(true));

        deleteAs(w.session, "/jobs/" + w.id("seed") + "/applied").andExpect(status().isNoContent());
        String unapplied = feed(w.session);
        assertThat(titlesOf(unapplied)).containsExactly("Python Data Engineer", "Gardener", "Python Data Developer");
        assertThat(adjustmentOf(unapplied, "Python Data Developer")).isZero();
    }

    // --- caps ---

    @Test
    void manyHidesAtOneCompanyCapThePenaltyAndNeverEmptyTheFeed() throws Exception {
        World w = world()
                .add("a", "Forklift Driver", "Initech", 50).add("b", "Welder", "Initech", 50)
                .add("c", "Plumber", "Initech", 50).add("d", "Roofer", "Initech", 50)
                .add("k", "Office Manager", "Initech", 90)
                .add("u", "Data Analyst", "Umbrella", 80)
                .stub();
        for (String key : List.of("a", "b", "c", "d")) {
            putAs(w.session, "/jobs/" + w.id(key) + "/hide").andExpect(status().isNoContent());
        }

        String body = feed(w.session);

        assertThat(adjustmentOf(body, "Office Manager")).isCloseTo(-30, within(0.05)); // 4 * 15 = 60, capped
        assertThat(feedScoreOf(body, "Office Manager")).isCloseTo(60, within(0.05));
        assertThat(codes(body, "Office Manager")).contains("HIDDEN_SAME_COMPANY", "PENALTY_CAPPED");
        assertThat(titlesOf(body)).containsExactly("Data Analyst", "Office Manager"); // demoted, still there
    }

    @Test
    void manySavesAtOneCompanyCapTheBoost() throws Exception {
        World w = world()
                .add("a", "Forklift Driver", "Hooli", 50).add("b", "Welder", "Hooli", 50)
                .add("c", "Plumber", "Hooli", 50).add("d", "Roofer", "Hooli", 50)
                .add("k", "Office Manager", "Hooli", 60)
                .stub();
        for (String key : List.of("a", "b", "c", "d")) {
            putAs(w.session, "/jobs/" + w.id(key) + "/save").andExpect(status().isNoContent());
        }

        String body = feed(w.session);

        assertThat(adjustmentOf(body, "Office Manager")).isCloseTo(15, within(0.05)); // 4 * 5 = 20, capped
        assertThat(codes(body, "Office Manager")).contains("SAVED_SAME_COMPANY", "BOOST_CAPPED");
    }

    @Test
    void theFeedScoreStaysWithinZeroAndOneHundred() throws Exception {
        World w = world()
                .add("a", "Forklift Driver", "Initech", 50).add("b", "Welder", "Initech", 50)
                .add("c", "Plumber", "Initech", 50)
                .add("k", "Office Manager", "Initech", 12)
                .stub();
        for (String key : List.of("a", "b", "c")) {
            putAs(w.session, "/jobs/" + w.id(key) + "/hide").andExpect(status().isNoContent());
        }

        assertThat(feedScoreOf(feed(w.session), "Office Manager")).isZero();
    }

    // --- authorization ---

    @CoversEndpoints({"GET /feed"})
    @Test
    void oneUsersActionsNeverAffectAnotherUsersFeed() throws Exception {
        World a = hidingWorld();
        Session otherSession = newSession();
        UUID other = userIdOf(otherSession);
        seedFor(other, 0, "Backend engineer", "java");
        saveEmptyPreferences(other);
        stubScores(other, a.scores);
        String beforeForOther = feed(otherSession);

        putAs(a.session, "/jobs/" + a.id("hidden") + "/hide").andExpect(status().isNoContent());
        putAs(a.session, "/jobs/" + a.id("unrelated") + "/applied").andExpect(status().isNoContent());
        String forA = feed(a.session);
        String afterForOther = feed(otherSession);

        assertThat(titlesOf(forA)).doesNotContain("Java Backend Engineer", "Data Analyst");
        assertThat(afterForOther).isEqualTo(beforeForOther);
        assertThat(titlesOf(afterForOther)).contains("Java Backend Engineer", "Data Analyst");
        assertThat(count("select count(*) from user_job_actions where user_id = ?", other)).isZero();
        getAs(otherSession, "/jobs/" + a.id("hidden")).andExpect(jsonPath("$.hidden").value(false))
                .andExpect(jsonPath("$.applied").value(false));
    }

    @Test
    void theFeedNeedsASignedInUser() throws Exception {
        mvc.perform(get("/feed")).andExpect(status().isUnauthorized());
    }

    // --- paging ---

    private World ladder() throws Exception {
        return world()
                .add("j1", "Zeta Specialist", "Co1", 90).add("j2", "Eta Planner", "Co2", 85)
                .add("j3", "Theta Auditor", "Co3", 80).add("j4", "Iota Broker", "Co4", 75)
                .add("j5", "Kappa Tester", "Co1", 70).add("j6", "Lambda Cook", "Co6", 65)
                .add("j7", "Mu Pilot", "Co7", 60)
                .stub();
    }

    @Test
    void pagingWalksTheWholeFeedOnceInTheSameOrderWithAdjustmentsInPlay() throws Exception {
        World w = ladder();
        putAs(w.session, "/jobs/" + w.id("j1") + "/hide").andExpect(status().isNoContent()); // demotes j5 (same company)
        putAs(w.session, "/jobs/" + w.id("j4") + "/save").andExpect(status().isNoContent());
        List<String> unpaged = titlesOf(feed(w.session));

        List<String> paged = new ArrayList<>();
        String url = "/feed?limit=2";
        for (int page = 0; page < 6; page++) {
            String body = getAs(w.session, url).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            paged.addAll(titlesOf(body));
            String next = cursorOf(body);
            if (next == null) {
                break;
            }
            url = "/feed?limit=2&cursor=" + next;
        }

        assertThat(unpaged).hasSize(6).doesNotContain("Zeta Specialist");
        assertThat(paged).containsExactlyElementsOf(unpaged);
        assertThat(unpaged.get(unpaged.size() - 1)).isEqualTo("Kappa Tester"); // 70 - 15 = 55, below Mu Pilot's 60
    }

    @Test
    void anActionMadeWhilePagingDoesNotRepeatOrSkipJobsOnTheNextPages() throws Exception {
        World w = ladder();
        String first = getAs(w.session, "/feed?limit=3").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(titlesOf(first)).containsExactly("Zeta Specialist", "Eta Planner", "Theta Auditor");
        String cursor = cursorOf(first);

        Thread.sleep(20);
        putAs(w.session, "/jobs/" + w.id("j1") + "/hide").andExpect(status().isNoContent());
        String second = getAs(w.session, "/feed?limit=3&cursor=" + cursor).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();

        // j5 shares Zeta's company and would now be demoted below j7, but the pages being walked keep their order
        assertThat(titlesOf(second)).containsExactly("Iota Broker", "Kappa Tester", "Lambda Cook");
        String third = getAs(w.session, "/feed?limit=3&cursor=" + cursorOf(second))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(titlesOf(third)).containsExactly("Mu Pilot");
        assertThat(cursorOf(third)).isNull();
        // a fresh first page has the feedback applied
        assertThat(titlesOf(feed(w.session))).containsExactly("Eta Planner", "Theta Auditor", "Iota Broker",
                "Lambda Cook", "Mu Pilot", "Kappa Tester");
    }

    @Test
    void aMalformedCursorIsABadRequest() throws Exception {
        World w = ladder();

        getAs(w.session, "/feed?cursor=not-a-cursor").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
        getAs(w.session, "/feed?limit=0").andExpect(status().isBadRequest());
    }

    // --- what the feed costs and says about its scores ---

    @Test
    void theFeedReadsCachedScoresAndAsksTheModelOnlyOnTheFirstLook() throws Exception {
        World w = hidingWorld();

        String first = feed(w.session);
        feed(w.session);
        putAs(w.session, "/jobs/" + w.id("hidden") + "/hide").andExpect(status().isNoContent());
        feed(w.session);
        feed(w.session);

        assertThat(scoreRequestCount(w.user)).isEqualTo(1);
        assertThat((List<String>) JsonPath.read(first, "$.items[*].scoreSource")).containsOnly("LLM_SCORED");
        assertThat((List<String>) JsonPath.read(first, "$.items[*].model")).containsOnly("test-model");
        assertThat((List<List<String>>) JsonPath.read(first, "$.items[*].strengths")).allSatisfy(s -> assertThat(s).isNotEmpty());
        assertThat((List<List<String>>) JsonPath.read(first, "$.items[*].gaps")).allSatisfy(s -> assertThat(s).isNotEmpty());
        assertThat((List<String>) JsonPath.read(first, "$.items[*].job.company.name")).contains("Globex", "Umbrella");
    }

    @Test
    void withoutAModelTheFeedShowsEstimatesLabelledAsSuch() throws Exception {
        World w = hidingWorld();
        stubStatus(w.user, 503, "{\"code\":\"llm_unavailable\"}");

        String body = feed(w.session);

        assertThat(titlesOf(body)).hasSize(4);
        assertThat((List<String>) JsonPath.read(body, "$.items[*].scoreSource")).containsOnly("STAGE2_ONLY");
        assertThat((List<Object>) JsonPath.read(body, "$.items[*].model")).isEmpty();
        assertThat((List<Number>) JsonPath.read(body, "$.items[*].matchScore")).allSatisfy(n -> assertThat(n.intValue())
                .isBetween(0, 100));
    }

    // --- empty states ---

    @Test
    void aUserWithoutAResumeIsToldToUploadOne() throws Exception {
        Session me = newSession();

        getAs(me, "/feed").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyReason").value("NO_RESUME"));
    }

    @Test
    void aUserWithAResumeButNoPreferencesIsToldToSetThem() throws Exception {
        Session me = newSession();
        seedFor(userIdOf(me), 0, "Backend engineer", "java");
        insert(spec().title("Java Engineer").embedding(unit(5)));

        getAs(me, "/feed").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyReason").value("NO_PREFERENCES"));
        assertThat(scoreRequestCount(userIdOf(me))).isZero();
    }

    @Test
    void aResumeStillBeingAnalysedIsReportedAsProcessing() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        Seeded seeded = seedFor(user, 0, "Backend engineer", "java");
        saveEmptyPreferences(user);
        insert(spec().title("Java Engineer").embedding(unit(5)));
        jdbc.update("update resume_versions set embedding_input_hash = 'stale' where id = ?", seeded.versionId());

        getAs(me, "/feed").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyReason").value("RESUME_PROCESSING"));
    }

    @Test
    void noMatchingJobsAndAFullyHiddenFeedBothSayNoMatches() throws Exception {
        World w = world();
        getAs(w.session, "/feed").andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyReason").value("NO_MATCHES"));

        w.add("only", "Java Engineer", "Acme", 80).stub();
        assertThat(titlesOf(feed(w.session))).containsExactly("Java Engineer");
        putAs(w.session, "/jobs/" + w.id("only") + "/hide").andExpect(status().isNoContent());

        getAs(w.session, "/feed").andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyReason").value("NO_MATCHES"));
    }

    @Test
    void aNonEmptyFeedHasNoEmptyReason() throws Exception {
        World w = hidingWorld();

        getAs(w.session, "/feed").andExpect(jsonPath("$.emptyReason").doesNotExist());
    }
}
