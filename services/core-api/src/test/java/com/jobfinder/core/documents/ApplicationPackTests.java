package com.jobfinder.core.documents;

import com.jobfinder.core.CoversEndpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;

/**
 * POST /jobs/{id}/application-pack and what hangs off it: the tailored CV, the cover letter and the screening answers
 * for a fixture job through the stubbed ai-service; reuse of an existing CV; idempotency and the double click; a part
 * that fails or hits the daily cap while the others are kept, and the retry; other options making the prose again;
 * billing per feature; who may see a pack; and account deletion.
 */
class ApplicationPackTests extends WritingTestSupport {

    private static final String CV = "TAILORED_RESUME";
    private static final String LETTER = "COVER_LETTER";
    private static final String ANSWERS = "SCREENING_ANSWERS";

    @Autowired
    private AiUsageLedger ledger;

    private record Setup(Session session, Candidate candidate, UUID job) {
    }

    private Setup setup() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubFactCheck(candidate);
        stubTextCheck(candidate);
        UUID userId = candidate.userId();
        stubTailor(userId, tailorOk(UUID.randomUUID(), "0.003"));
        stubLetter(userId, letterOk(candidate, UUID.randomUUID(), "0.002"));
        stubAnswers(userId, answersOk(candidate, UUID.randomUUID(), "0.001"));
        return new Setup(me, candidate, newJob());
    }

    /** A filter path such as {@code $.parts[?(@.type == 'X')].document.id} yields a one-element list. */
    private static String firstOf(String json, String path) {
        Object found = com.jayway.jsonpath.JsonPath.read(json, path);
        return (String) (found instanceof java.util.List<?> list ? list.get(0) : found);
    }

    private static String part(String type, String field) {
        return "$.parts[?(@.type == '" + type + "')]." + field;
    }

    private int calls(UUID userId, String feature) {
        return jdbc.queryForObject("select count(*) from ai_calls where user_id = ? and feature = ?", Integer.class,
                userId, feature);
    }

    private void exhaustTheCap(UUID userId) {
        com.jobfinder.core.TestCredits.seed(jdbc, userId);
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), userId, "parse_resume", "test", "m", 1, 1,
                new BigDecimal("5.00"), 1, "p/v1", "test", AiCallStatus.SUCCEEDED));
    }

    private void resetTheCap(UUID userId) {
        jdbc.update("delete from credit_ledger where user_id = ?", userId);
    }

    // ------------------------------------------------------------------------------------------------- generate

    @Test
    void itRequiresASignedInUser() throws Exception {
        mvc.perform(post("/jobs/" + UUID.randomUUID() + "/application-pack")).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/application-packs/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/application-packs"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/application-packs/" + UUID.randomUUID() + "/retry")).andExpect(status().isUnauthorized());
    }

    @Test
    void noResumeAndAnUnknownJobAreRefusedBeforeAnythingIsMade() throws Exception {
        Session me = newSession();
        createPack(me, newJob(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resume_required"));
        Candidate candidate = seed(me);

        createPack(me, UUID.randomUUID(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));

        assertThat(countRows("application_packs", candidate.userId())).isZero();
    }

    @Test
    void aPackForAFixtureJobHasTheCvTheLetterAndTheAnswers() throws Exception {
        Setup s = setup();

        ResultActions result = createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.job.id").value(s.job().toString()))
                .andExpect(jsonPath("$.job.title").value("Staff Backend Engineer"))
                .andExpect(jsonPath("$.baseResumeVersionId").value(s.candidate().versionId().toString()))
                .andExpect(jsonPath("$.options.tone").value("FORMAL"))
                .andExpect(jsonPath("$.options.length").value("STANDARD"))
                .andExpect(jsonPath("$.options.include.length()").value(3))
                .andExpect(jsonPath("$.parts.length()").value(3))
                .andExpect(jsonPath("$.parts[0].type").value(CV))
                .andExpect(jsonPath("$.parts[1].type").value(LETTER))
                .andExpect(jsonPath("$.parts[2].type").value(ANSWERS))
                .andExpect(jsonPath(part(CV, "state")).value("READY"))
                .andExpect(jsonPath(part(CV, "document.type")).value(CV))
                .andExpect(jsonPath(part(CV, "document.status")).value("DRAFT"))
                .andExpect(jsonPath(part(LETTER, "state")).value("READY"))
                .andExpect(jsonPath(part(LETTER, "document.content.salutation")).value("Dear Hiring Manager,"))
                .andExpect(jsonPath(part(LETTER, "document.content.sender.full_name")).value(s.candidate().name()))
                .andExpect(jsonPath(part(ANSWERS, "state")).value("READY"))
                .andExpect(jsonPath(part(ANSWERS, "document.content.answers.length()")).value(10));
        String id = idOf(result);

        assertThat(documents(s.candidate().userId())).isEqualTo(3);
        getPack(s.session(), id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.parts.length()").value(3));
        // The documents are the ordinary ones: each is reviewed, edited and approved on its own.
        String letter = firstOf(body(getPack(s.session(), id)), part(LETTER, "document.id"));
        patchAs(s.session(), letter, edit(1, "salutation", "Dear Ms Okafor,")).andExpect(status().isOk());
        approve(s.session(), letter).andExpect(status().isOk());
        getPack(s.session(), id).andExpect(jsonPath(part(LETTER, "document.status")).value("APPROVED"))
                .andExpect(jsonPath(part(LETTER, "document.content.salutation")).value("Dear Ms Okafor,"))
                .andExpect(jsonPath(part(CV, "document.status")).value("DRAFT"));
    }

    @Test
    void usageIsRecordedPerFeatureForTheCaller() throws Exception {
        Setup s = setup();
        UUID tailorCall = UUID.randomUUID();
        UUID letterCall = UUID.randomUUID();
        UUID answersCall = UUID.randomUUID();
        stubTailor(s.candidate().userId(), tailorOk(tailorCall, "0.003"));
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), letterCall, "0.002"));
        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), answersCall, "0.001"));

        createPack(s.session(), s.job(), null).andExpect(status().isCreated());

        assertThat(featureOf(tailorCall)).isEqualTo("tailor_resume");
        assertThat(featureOf(letterCall)).isEqualTo("cover_letter");
        assertThat(featureOf(answersCall)).isEqualTo("screening_answers");
        assertThat(jdbc.queryForObject("select sum(cost_micro_usd) from ai_calls where user_id = ?", Long.class,
                s.candidate().userId())).isEqualTo(6_000L);
        assertThat(countRows("credit_ledger", s.candidate().userId())).isEqualTo(3);
    }

    @Test
    void theSameRequestAgainReturnsThePackAndCallsNothing() throws Exception {
        Setup s = setup();
        String first = idOf(createPack(s.session(), s.job(), null).andExpect(status().isCreated()));

        String second = idOf(createPack(s.session(), s.job(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE")));

        assertThat(second).isEqualTo(first);
        assertThat(tailorRequests(s.candidate().userId())).hasSize(1);
        assertThat(letterRequests(s.candidate().userId())).hasSize(1);
        assertThat(answersRequests(s.candidate().userId())).hasSize(1);
        assertThat(countRows("application_packs", s.candidate().userId())).isEqualTo(1);
        assertThat(documents(s.candidate().userId())).isEqualTo(3);
    }

    @Test
    void anExistingCvForTheJobAndResumeIsReusedNotTailoredAgain() throws Exception {
        Setup s = setup();
        String cv = idOf(tailor(s.session(), s.job()).andExpect(status().isCreated()));
        approve(s.session(), cv).andExpect(status().isOk());

        createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath(part(CV, "document.id")).value(cv))
                .andExpect(jsonPath(part(CV, "document.status")).value("APPROVED"))
                .andExpect(jsonPath("$.status").value("COMPLETE"));

        assertThat(tailorRequests(s.candidate().userId())).hasSize(1);
        assertThat(documents(s.candidate().userId())).isEqualTo(3);
    }

    @Test
    void anExistingLetterWithTheSameOptionsIsReusedToo() throws Exception {
        Setup s = setup();
        String letter = idOf(coverLetter(s.session(), s.job(), null).andExpect(status().isCreated()));

        createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath(part(LETTER, "document.id")).value(letter));

        assertThat(letterRequests(s.candidate().userId())).hasSize(1);
    }

    @Test
    void thePartsCanBeChosenAndOnlyThoseAreMade() throws Exception {
        Setup s = setup();

        createPack(s.session(), s.job(), "{\"tone\":\"WARM\",\"length\":\"SHORT\",\"include\":[\"COVER_LETTER\"]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.parts.length()").value(1)).andExpect(jsonPath("$.parts[0].type").value(LETTER))
                .andExpect(jsonPath("$.options.tone").value("WARM"));

        assertThat(tailorRequests(s.candidate().userId())).isEmpty();
        assertThat(answersRequests(s.candidate().userId())).isEmpty();
        assertThat(documents(s.candidate().userId())).isEqualTo(1);
    }

    @Test
    void badOptionsAreRefusedBeforeAnythingIsMade() throws Exception {
        Setup s = setup();

        createPack(s.session(), s.job(), "{\"tone\":\"SHOUTY\"}").andExpect(status().isBadRequest());
        createPack(s.session(), s.job(), "{\"include\":[]}").andExpect(status().isBadRequest());
        createPack(s.session(), s.job(), "{\"include\":[\"RESUME\"]}").andExpect(status().isBadRequest());
        createPack(s.session(), s.job(), "{\"notes\":\"" + "x".repeat(1001) + "\"}").andExpect(status().isBadRequest());

        assertThat(countRows("application_packs", s.candidate().userId())).isZero();
        assertThat(documents(s.candidate().userId())).isZero();
    }

    @Test
    void aLetterThatFailsTheFactCheckIsStillReadyAndShowsItsFlags() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), letterBlocked(s.candidate(), UUID.randomUUID()));

        createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath(part(LETTER, "state")).value("READY"))
                .andExpect(jsonPath(part(LETTER, "document.status")).value("FACT_CHECK_FAILED"))
                .andExpect(jsonPath(part(LETTER, "document.factCheck.flags[0].code")).value("NEW_EMPLOYER"));
    }

    // ---------------------------------------------------------------------------------------- partial failure

    @Test
    void aPartThatFailsIsRecordedWithATypedErrorAndTheOthersAreKept() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), 503, "{\"code\":\"llm_unavailable\"}");

        createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath(part(CV, "state")).value("READY"))
                .andExpect(jsonPath(part(ANSWERS, "state")).value("READY"))
                .andExpect(jsonPath(part(LETTER, "state")).value("FAILED"))
                .andExpect(jsonPath(part(LETTER, "error.code")).value("writing_unavailable"))
                .andExpect(jsonPath(part(LETTER, "error.retryable")).value(true))
                .andExpect(jsonPath(part(LETTER, "error.message")).isNotEmpty())
                .andExpect(jsonPath(part(LETTER, "document")).isEmpty());

        assertThat(documents(s.candidate().userId())).isEqualTo(2);
    }

    @Test
    void retryMakesOnlyThePartsThatNeedIt() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), 503, "{\"code\":\"llm_unavailable\"}");
        String id = idOf(createPack(s.session(), s.job(), null).andExpect(status().isCreated()));
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.002"));

        retryPack(s.session(), id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath(part(LETTER, "state")).value("READY"))
                .andExpect(jsonPath(part(LETTER, "document.type")).value(LETTER))
                .andExpect(jsonPath(part(LETTER, "error")).isEmpty());

        assertThat(tailorRequests(s.candidate().userId())).hasSize(1);
        assertThat(answersRequests(s.candidate().userId())).hasSize(1);
        assertThat(letterRequests(s.candidate().userId())).hasSize(2);
        // Nothing left to retry: the pack comes back as it is.
        retryPack(s.session(), id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETE"));
        assertThat(letterRequests(s.candidate().userId())).hasSize(2);
        getPack(s.session(), id).andExpect(jsonPath("$.status").value("COMPLETE"));
    }

    @Test
    void retryCanNameThePartsAndRefusesAPartThePackDoesNotHave() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), 503, "{}");
        stubAnswers(s.candidate().userId(), 503, "{}");
        String id = idOf(createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PARTIAL")));
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.002"));
        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), UUID.randomUUID(), "0.001"));

        retryPack(s.session(), id, "{\"parts\":[\"COVER_LETTER\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL")).andExpect(jsonPath(part(LETTER, "state")).value("READY"))
                .andExpect(jsonPath(part(ANSWERS, "state")).value("FAILED"));
        retryPack(s.session(), id, "{\"parts\":[\"COVER_LETTER\",\"SCREENING_ANSWERS\"]}")
                .andExpect(jsonPath("$.status").value("COMPLETE"));
        String letterOnly = idOf(createPack(s.session(), injectedJob(), "{\"include\":[\"COVER_LETTER\"]}")
                .andExpect(status().isCreated()));
        retryPack(s.session(), letterOnly, "{\"parts\":[\"SCREENING_ANSWERS\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_part"));
    }

    @Test
    void whenEveryPartFailsThePackIsFailedAndRetryCanRecoverIt() throws Exception {
        Setup s = setup();
        stubTailor(s.candidate().userId(), 503, "{\"code\":\"llm_unavailable\"}");
        stubLetter(s.candidate().userId(), 503, "{}");
        stubAnswers(s.candidate().userId(), 503, "{}");

        String id = idOf(createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath(part(CV, "state")).value("FAILED"))
                .andExpect(jsonPath(part(CV, "error.code")).value("tailoring_unavailable")));
        assertThat(documents(s.candidate().userId())).isZero();

        stubTailor(s.candidate().userId(), tailorOk(UUID.randomUUID(), "0.003"));
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.002"));
        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), UUID.randomUUID(), "0.001"));
        retryPack(s.session(), id, null).andExpect(jsonPath("$.status").value("COMPLETE"));
        assertThat(documents(s.candidate().userId())).isEqualTo(3);
    }

    @Test
    void aPartThatHitsTheDailyCapIsBlockedByCapWithTheResetTimeAndOthersAreKept() throws Exception {
        Setup s = setup();
        // The CV is made first (and reused afterwards); then the day's allowance is used up.
        String cv = idOf(tailor(s.session(), s.job()).andExpect(status().isCreated()));
        exhaustTheCap(s.candidate().userId());

        String id = idOf(createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath(part(CV, "state")).value("READY"))
                .andExpect(jsonPath(part(CV, "document.id")).value(cv))
                .andExpect(jsonPath(part(LETTER, "state")).value("BLOCKED_BY_CAP"))
                .andExpect(jsonPath(part(LETTER, "error.code")).value("ai_daily_cap_reached"))
                .andExpect(jsonPath(part(LETTER, "error.retryable")).value(true))
                .andExpect(jsonPath(part(LETTER, "error.resetsAt")).isNotEmpty())
                .andExpect(jsonPath(part(ANSWERS, "state")).value("BLOCKED_BY_CAP")));
        assertThat(letterRequests(s.candidate().userId())).isEmpty();
        assertThat(answersRequests(s.candidate().userId())).isEmpty();

        // Still over the cap: the retry changes nothing and calls nothing.
        retryPack(s.session(), id, null).andExpect(jsonPath(part(LETTER, "state")).value("BLOCKED_BY_CAP"));
        assertThat(letterRequests(s.candidate().userId())).isEmpty();

        // The next day's allowance: the retry makes what was blocked.
        resetTheCap(s.candidate().userId());
        retryPack(s.session(), id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath(part(LETTER, "state")).value("READY"))
                .andExpect(jsonPath(part(ANSWERS, "state")).value("READY"));
        assertThat(tailorRequests(s.candidate().userId())).hasSize(1);
    }

    @Test
    void withTheCapAlreadyUsedUpTheWholePackIsBlockedAndNothingIsCalled() throws Exception {
        Setup s = setup();
        exhaustTheCap(s.candidate().userId());

        createPack(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath(part(CV, "state")).value("BLOCKED_BY_CAP"))
                .andExpect(jsonPath(part(LETTER, "state")).value("BLOCKED_BY_CAP"))
                .andExpect(jsonPath(part(ANSWERS, "state")).value("BLOCKED_BY_CAP"));

        assertThat(tailorRequests(s.candidate().userId())).isEmpty();
        assertThat(letterRequests(s.candidate().userId())).isEmpty();
        assertThat(answersRequests(s.candidate().userId())).isEmpty();
        assertThat(documents(s.candidate().userId())).isZero();
    }

    // ------------------------------------------------------------------------------------------- other options

    @Test
    void otherOptionsWriteTheLetterAndAnswersAgainKeepTheOldOnesAsHistoryAndReuseTheCv() throws Exception {
        Setup s = setup();
        ResultActions first = createPack(s.session(), s.job(), null).andExpect(status().isCreated());
        String pack = idOf(first);
        String cv = firstOf(body(first), part(CV, "document.id"));
        String oldLetter = firstOf(body(first), part(LETTER, "document.id"));
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.002"));
        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), UUID.randomUUID(), "0.001"));

        ResultActions again = createPack(s.session(), s.job(), "{\"tone\":\"WARM\",\"length\":\"SHORT\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(pack))
                .andExpect(jsonPath("$.status").value("COMPLETE")).andExpect(jsonPath("$.options.tone").value("WARM"))
                .andExpect(jsonPath(part(CV, "document.id")).value(cv))
                .andExpect(jsonPath(part(LETTER, "document.options.tone")).value("WARM"));
        String newLetter = firstOf(body(again), part(LETTER, "document.id"));

        assertThat(newLetter).isNotEqualTo(oldLetter);
        assertThat(statusOf(oldLetter)).isEqualTo("SUPERSEDED");
        assertThat(tailorRequests(s.candidate().userId())).hasSize(1);
        assertThat(letterRequests(s.candidate().userId())).hasSize(2);
        assertThat(answersRequests(s.candidate().userId())).hasSize(2);
        assertThat(countRows("application_packs", s.candidate().userId())).isEqualTo(1);
    }

    @Test
    void anApprovedLetterSurvivesAPackRegenerationAndTheNewDraftSitsBesideIt() throws Exception {
        Setup s = setup();
        ResultActions first = createPack(s.session(), s.job(), null).andExpect(status().isCreated());
        String letter = firstOf(body(first), part(LETTER, "document.id"));
        approve(s.session(), letter).andExpect(status().isOk());
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.002"));
        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), UUID.randomUUID(), "0.001"));

        ResultActions again = createPack(s.session(), s.job(), "{\"tone\":\"CONCISE\"}").andExpect(status().isOk());

        String newLetter = firstOf(body(again), part(LETTER, "document.id"));
        assertThat(newLetter).isNotEqualTo(letter);
        assertThat(statusOf(letter)).isEqualTo("APPROVED");
        assertThat(statusOf(newLetter)).isEqualTo("DRAFT");
    }

    @Test
    void aDeletedDraftShowsAsMissingAndRetryMakesANewOne() throws Exception {
        Setup s = setup();
        ResultActions first = createPack(s.session(), s.job(), null).andExpect(status().isCreated());
        String pack = idOf(first);
        String letter = firstOf(body(first), part(LETTER, "document.id"));
        deleteDocument(s.session(), letter).andExpect(status().isNoContent());
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.002"));

        getPack(s.session(), pack).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath(part(LETTER, "state")).value("MISSING"))
                .andExpect(jsonPath(part(LETTER, "error.code")).value("document_missing"))
                .andExpect(jsonPath(part(CV, "state")).value("READY"));
        retryPack(s.session(), pack, null).andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath(part(LETTER, "state")).value("READY"));
        assertThat(documents(s.candidate().userId())).isEqualTo(3);
    }

    @Test
    void aLetterRegeneratedOnItsOwnIsFollowedByThePack() throws Exception {
        Setup s = setup();
        String pack = idOf(createPack(s.session(), s.job(), null).andExpect(status().isCreated()));
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.002"));
        String newer = idOf(coverLetter(s.session(), s.job(), "{\"tone\":\"WARM\"}").andExpect(status().isCreated()));

        getPack(s.session(), pack).andExpect(jsonPath(part(LETTER, "state")).value("READY"))
                .andExpect(jsonPath(part(LETTER, "document.id")).value(newer))
                .andExpect(jsonPath("$.status").value("COMPLETE"));
    }

    // ------------------------------------------------------------------------------------- double click

    @Test
    void aDoubleClickMakesOnePackAndOneCallPerPart() throws Exception {
        Setup s = setup();
        stubSlowLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.002"), 800);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Map.Entry<Integer, String>> click = () -> {
                var response = createPack(s.session(), s.job(), null).andReturn().getResponse();
                return Map.entry(response.getStatus(), JsonPath.<String>read(response.getContentAsString(), "$.id"));
            };
            Future<Map.Entry<Integer, String>> a = pool.submit(click);
            Future<Map.Entry<Integer, String>> b = pool.submit(click);
            var first = a.get();
            var second = b.get();

            assertThat(Set.of(first.getKey(), second.getKey())).containsExactlyInAnyOrder(200, 201);
            assertThat(first.getValue()).isEqualTo(second.getValue());
        } finally {
            pool.shutdownNow();
        }
        assertThat(countRows("application_packs", s.candidate().userId())).isEqualTo(1);
        assertThat(tailorRequests(s.candidate().userId())).hasSize(1);
        assertThat(letterRequests(s.candidate().userId())).hasSize(1);
        assertThat(answersRequests(s.candidate().userId())).hasSize(1);
        assertThat(documents(s.candidate().userId())).isEqualTo(3);
    }

    // ------------------------------------------------------------------------------------------------- authz

    @CoversEndpoints({"GET /application-packs/{id}", "POST /application-packs/{id}/retry", "GET /application-packs", "POST /jobs/{id}/application-pack"})
    @Test
    void aPackBelongsToItsOwnerAlone() throws Exception {
        Setup s = setup();
        String id = idOf(createPack(s.session(), s.job(), null).andExpect(status().isCreated()));
        Session other = newSession();
        Candidate theirs = seed(other);
        stubTextCheck(theirs);

        getPack(other, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("pack_not_found"));
        retryPack(other, id, null).andExpect(status().isNotFound());
        listPacks(other, "").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        listPacks(other, "?jobId=" + s.job()).andExpect(jsonPath("$.items.length()").value(0));
        // The other user makes their own pack for the same job: separate rows, separate documents.
        stubTailor(theirs.userId(), tailorOk(UUID.randomUUID(), "0.003"));
        stubLetter(theirs.userId(), letterOk(theirs, UUID.randomUUID(), "0.002"));
        stubAnswers(theirs.userId(), answersOk(theirs, UUID.randomUUID(), "0.001"));
        stubFactCheck(theirs);
        String theirPack = idOf(createPack(other, s.job(), null).andExpect(status().isCreated()));
        assertThat(theirPack).isNotEqualTo(id);
        assertThat(body(getPack(other, theirPack))).contains(theirs.name()).doesNotContain(s.candidate().name());
        getPack(s.session(), id).andExpect(status().isOk());
    }

    @Test
    void packsAreListedNewestFirstAndCanBeFilteredByJob() throws Exception {
        Setup s = setup();
        UUID second = injectedJob();
        String a = idOf(createPack(s.session(), s.job(), "{\"include\":[\"COVER_LETTER\"]}")
                .andExpect(status().isCreated()));
        String b = idOf(createPack(s.session(), second, "{\"include\":[\"COVER_LETTER\"]}")
                .andExpect(status().isCreated()));

        listPacks(s.session(), "").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(b)).andExpect(jsonPath("$.items[1].id").value(a))
                .andExpect(jsonPath("$.items[0].status").value("COMPLETE"))
                .andExpect(jsonPath("$.items[0].job.id").value(second.toString()));
        listPacks(s.session(), "?jobId=" + s.job()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(a));
        listPacks(s.session(), "?limit=0").andExpect(status().isBadRequest());
        getPack(s.session(), UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------- deletion

    @Test
    void deletingTheAccountRemovesItsPacksAndDocumentsAndOnlyIts() throws Exception {
        Setup leaving = setup();
        Setup bystander = setup();
        String letter = firstOf(body(createPack(leaving.session(), leaving.job(), null)
                .andExpect(status().isCreated())), part(LETTER, "document.id"));
        approve(leaving.session(), letter).andExpect(status().isOk());
        createPack(bystander.session(), bystander.job(), null).andExpect(status().isCreated());
        assertThat(documents(leaving.candidate().userId())).isEqualTo(3);

        mvc.perform(delete("/me").header("Authorization", bearer(leaving.session()))).andExpect(status().isNoContent());

        assertThat(countRows("application_packs", leaving.candidate().userId())).isZero();
        assertThat(documents(leaving.candidate().userId())).isZero();
        assertThat(countRows("application_packs", bystander.candidate().userId())).isEqualTo(1);
        assertThat(documents(bystander.candidate().userId())).isEqualTo(3);
    }
}
