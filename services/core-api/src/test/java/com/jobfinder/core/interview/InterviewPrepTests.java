package com.jobfinder.core.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.AiServiceStubs;
import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;

import tools.jackson.databind.node.ObjectNode;

/**
 * POST /interview-prep: what is sent to ai-service, what is stored, idempotency per (user, job, prompt version) and the
 * double click, the daily cap, ai-service failures and answers that are not what the contract promises, and usage
 * recording. ai-service is WireMock serving the contract files its own tests pin; no test talks to a model.
 */
class InterviewPrepTests extends InterviewTestSupport {

    @Autowired
    private AiUsageLedger ledger;

    @Test
    void itRequiresASignedInUser() throws Exception {
        mvc.perform(post("/interview-prep").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aUserWithoutAParsedResumeGetsAConflict() throws Exception {
        Session me = newSession();

        generate(me, prepJob()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resume_required"));

        assertThat(preps(userIdOf(me))).isZero();
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        Session me = newSession();
        seed(me);

        generate(me, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
    }

    @Test
    void aRequestWithoutAJobIdIsABadRequest() throws Exception {
        Session me = newSession();
        seed(me);

        mvc.perform(post("/interview-prep").header("Authorization", bearer(me))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void aFaithfulPrepIsStoredWithCategorisedQuestionsAndABriefWithSourcesAndUnknowns() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));

        String json = body(generate(me, job).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.jobId").value(job.toString()))
                .andExpect(jsonPath("$.jobTitle").value("Backend Engineer"))
                .andExpect(jsonPath("$.jobCompany").value("Harbor Freight Tech"))
                .andExpect(jsonPath("$.promptVersion").value("interview/v1"))
                .andExpect(jsonPath("$.model").value("fake-strong"))
                .andExpect(jsonPath("$.questions.length()").value(9))
                .andExpect(jsonPath("$.companyBrief.model").value("fake-fast"))
                .andExpect(jsonPath("$.companyBrief.droppedClaims").value(0)));

        List<String> categories = JsonPath.read(json, "$.questions[*].category");
        assertThat(Set.copyOf(categories)).containsExactlyInAnyOrder("behavioral", "technical", "role_specific");
        List<String> sources = JsonPath.read(json, "$.companyBrief.sections[*].claims[*].source");
        assertThat(sources).isNotEmpty().allMatch(s -> s.startsWith("job.") || s.startsWith("company."));
        List<String> evidence = JsonPath.read(json, "$.companyBrief.sections[*].claims[*].evidence");
        assertThat(evidence).doesNotContainNull().allMatch(e -> !e.isBlank());
        List<String> unknowns = JsonPath.read(json, "$.companyBrief.unknowns");
        assertThat(unknowns).isNotEmpty();

        assertThat(preps(candidate.userId())).isEqualTo(1);
        assertThat(questionRows(candidate.userId())).isEqualTo(9);
        assertThat(briefRows(candidate.userId())).isEqualTo(1);
    }

    @Test
    void theStoredPrepIsReadBackUnchanged() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        String created = body(generate(me, prepJob()).andExpect(status().isCreated()));

        String read = body(getPrep(me, JsonPath.read(created, "$.id")).andExpect(status().isOk()));

        assertThat(mapper.readTree(read)).isEqualTo(mapper.readTree(created));
    }

    @Test
    void aiServiceGetsTheResumeTheJobAndTheCompanyRecordWithTheServiceToken() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));

        generate(me, prepJob()).andExpect(status().isCreated());

        List<LoggedRequest> sent = prepRequests(candidate.userId());
        assertThat(sent).hasSize(1);
        String request = sent.get(0).getBodyAsString();
        assertThat(sent.get(0).getHeader("X-Service-Token")).isEqualTo(AiServiceStubs.TOKEN);
        assertThat(JsonPath.<String>read(request, "$.prompt_version")).isEqualTo("interview/v1");
        assertThat(JsonPath.<Integer>read(request, "$.question_count")).isEqualTo(12);
        assertThat(JsonPath.<String>read(request, "$.resume.contact.full_name")).isEqualTo(candidate.name());
        assertThat(JsonPath.<String>read(request, "$.job.title")).isEqualTo("Backend Engineer");
        assertThat(JsonPath.<String>read(request, "$.job.description")).contains("Spring Boot");
        assertThat(JsonPath.<String>read(request, "$.job.location")).isEqualTo("Lagos, Nigeria");
        assertThat(JsonPath.<List<String>>read(request, "$.job.skills")).containsExactly("Java", "Kafka",
                "Kubernetes");
        assertThat(JsonPath.<String>read(request, "$.company.name")).isEqualTo("Harbor Freight Tech");
        assertThat(JsonPath.<String>read(request, "$.company.size")).isEqualTo("201-500");
        assertThat(JsonPath.<String>read(request, "$.company.industry")).isEqualTo("Logistics software");
    }

    @Test
    void usageOfBothCallsIsRecordedUnderTheInterviewFeatureForTheCaller() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID questionsCall = UUID.randomUUID();
        UUID briefCall = UUID.randomUUID();
        stubPrep(candidate.userId(), prepOk(questionsCall, briefCall, "0.003"));

        generate(me, prepJob()).andExpect(status().isCreated());

        for (UUID call : List.of(questionsCall, briefCall)) {
            Map<String, Object> row = jdbc.queryForMap("select * from ai_calls where request_key = ?",
                    "ai-service:" + call);
            assertThat(row).containsEntry("user_id", candidate.userId()).containsEntry("feature", "interview_prep")
                    .containsEntry("cost_micro_usd", 3_000L).containsEntry("status", "SUCCEEDED")
                    .containsEntry("prompt_version", "interview/v1");
        }
        assertThat(aiCalls(candidate.userId())).isEqualTo(2);
    }

    // --- idempotency ---

    @Test
    void aRepeatRequestReturnsTheStoredPrepWithoutAnotherCallOrCharge() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));

        String first = idOf(generate(me, job).andExpect(status().isCreated()));
        String second = idOf(generate(me, job).andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(prepRequests(candidate.userId())).hasSize(1);
        assertThat(preps(candidate.userId())).isEqualTo(1);
        assertThat(aiCalls(candidate.userId())).isEqualTo(2);
    }

    @Test
    void aRepeatRequestIsServedEvenWhenTheDailyCapIsSpentSince() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        String first = idOf(generate(me, job).andExpect(status().isCreated()));
        spendTheCap(candidate.userId());

        generate(me, job).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first));

        assertThat(prepRequests(candidate.userId())).hasSize(1);
    }

    @Test
    void anotherPromptVersionIsAnotherPrepAndDoesNotBlockTheFirst() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        String current = idOf(generate(me, job).andExpect(status().isCreated()));
        // A prep made with an older prompt, as a later release would leave it.
        UUID old = UUID.randomUUID();
        jdbc.update("update interview_prep set prompt_version = 'interview/v0' where id = ?::uuid", current);
        jdbc.update("""
                insert into interview_prep (id, user_id, job_id, job_title, job_company, status, prompt_version,
                        created_at, updated_at)
                values (?, ?, ?, 'Backend Engineer', 'Harbor Freight Tech', 'GENERATING', 'interview/v2', now(), now())
                """, old, candidate.userId(), job);

        String again = idOf(generate(me, job).andExpect(status().isCreated()));

        assertThat(again).isNotEqualTo(current).isNotEqualTo(old.toString());
        assertThat(preps(candidate.userId())).isEqualTo(3);
    }

    @Test
    void aDoubleClickMakesOnePrepAndOneModelCall() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        stubSlowPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"), 800);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Map.Entry<Integer, String>> click = () -> {
                var response = generate(me, job).andReturn().getResponse();
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
        assertThat(prepRequests(candidate.userId())).hasSize(1);
        assertThat(preps(candidate.userId())).isEqualTo(1);
    }

    @Test
    void aFreshGeneratingRowIsReturnedAsIsAndNothingIsCalled() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        UUID running = UUID.randomUUID();
        placeholder(running, candidate.userId(), job, "now()");

        generate(me, job).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(running.toString()))
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.questions.length()").value(0));

        assertThat(prepRequests(candidate.userId())).isEmpty();
    }

    @Test
    void aGeneratingRowLeftByADeadRequestIsReplaced() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        UUID stale = UUID.randomUUID();
        placeholder(stale, candidate.userId(), job, "now() - interval '1 hour'");
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));

        String id = idOf(generate(me, job).andExpect(status().isCreated()).andExpect(jsonPath("$.status")
                .value("READY")));

        assertThat(id).isNotEqualTo(stale.toString());
        assertThat(preps(candidate.userId())).isEqualTo(1);
    }

    // --- cap and failures ---

    @Test
    void anExhaustedDailyCapBlocksWithTheTypedErrorBeforeAnyCall() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        spendTheCap(candidate.userId());

        generate(me, prepJob()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ai_daily_cap_reached"))
                .andExpect(jsonPath("$.resetsAt").isString()).andExpect(header().exists("Retry-After"));

        assertThat(prepRequests(candidate.userId())).isEmpty();
        assertThat(preps(candidate.userId())).isZero();
    }

    @Test
    void whenAiServiceIsDownTheAnswerIs503AndNoPlaceholderIsLeftBehind() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        stubPrep(candidate.userId(), 503, "{\"code\":\"llm_unavailable\"}");

        generate(me, job).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("interview_prep_unavailable"));

        assertThat(preps(candidate.userId())).isZero();
        // Once ai-service is back, the same request just works.
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        generate(me, job).andExpect(status().isCreated());
    }

    @Test
    void aPrepMadeWithAnotherPromptThanAskedForIsRefusedAndItsCostRecordedAsFailed() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID questionsCall = UUID.randomUUID();
        ObjectNode wrong = prepOk(questionsCall, UUID.randomUUID(), "0.003");
        wrong.put("prompt_version", "interview/v9");
        stubPrep(candidate.userId(), wrong);

        generate(me, prepJob()).andExpect(status().isServiceUnavailable());

        assertThat(preps(candidate.userId())).isZero();
        assertThat(callStatus(questionsCall)).isEqualTo("FAILED");
    }

    @Test
    void aClaimThatCitesAFieldThatWasNeverSentIsRefused() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID questionsCall = UUID.randomUUID();
        ObjectNode bad = prepOk(questionsCall, UUID.randomUUID(), "0.003");
        firstClaim(bad).put("source", "company.funding");
        stubPrep(candidate.userId(), bad);

        generate(me, prepJob()).andExpect(status().isServiceUnavailable());

        assertThat(preps(candidate.userId())).isZero();
        assertThat(callStatus(questionsCall)).isEqualTo("FAILED");
    }

    @Test
    void aClaimWhoseEvidenceIsNotInTheFieldItCitesIsRefused() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        ObjectNode bad = prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003");
        firstClaim(bad).put("evidence", "was acquired by Globex Corporation in 2021");
        stubPrep(candidate.userId(), bad);

        generate(me, prepJob()).andExpect(status().isServiceUnavailable());

        assertThat(preps(candidate.userId())).isZero();
    }

    @Test
    void aPrepWithoutAQuestionCategoryIsRefused() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        ObjectNode bad = prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003");
        var questions = (tools.jackson.databind.node.ArrayNode) bad.get("questions");
        for (int i = 0; i < questions.size(); i++) {
            if ("technical".equals(questions.get(i).get("category").asString())) {
                ((ObjectNode) questions.get(i)).put("category", "behavioral");
            }
        }
        stubPrep(candidate.userId(), bad);

        generate(me, prepJob()).andExpect(status().isServiceUnavailable());

        assertThat(preps(candidate.userId())).isZero();
    }

    // --- untrusted text ---

    @Test
    void whatTheModelWasTrickedIntoSayingByAJobPostingNeverReachesTheStoredPrep() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob(DESCRIPTION + " " + INJECTION);
        // ai-service's own contract file for a model that obeyed the injection: it dropped what was ungrounded.
        stubPrep(candidate.userId(), prepInjected(UUID.randomUUID(), UUID.randomUUID(), "0.003"));

        String json = body(generate(me, job).andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyBrief.droppedClaims").value(4)));

        assertThat(json).doesNotContain("Globex").doesNotContain("40000").doesNotContain("acquired");
        String stored = jdbc.queryForObject("select sections::text || unknowns::text from company_briefs b "
                + "join interview_prep p on p.id = b.prep_id where p.user_id = ?", String.class,
                candidate.userId());
        assertThat(stored).doesNotContain("Globex").doesNotContain("40000");
        // The injection really was in what was sent: the defence is ai-service's, and core-api stored what it kept.
        assertThat(prepRequests(candidate.userId()).get(0).getBodyAsString()).contains("Ignore all previous");
    }

    @Test
    void aClaimThatReplaysTheInjectionIsRefusedByCoreApiToo() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        ObjectNode obeyed = prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003");
        // A compromised or buggy ai-service answer: a claim whose evidence is not in anything that was sent.
        firstClaim(obeyed).put("statement", "The company was acquired by Globex Corporation in 2021.")
                .put("evidence", "acquired by Globex Corporation in 2021");
        stubPrep(candidate.userId(), obeyed);

        generate(me, job).andExpect(status().isServiceUnavailable());

        assertThat(preps(candidate.userId())).isZero();
    }

    // --- account deletion ---

    @Test
    void deletingTheAccountRemovesItsPrepsAndNobodyElses() throws Exception {
        Session leaving = newSession();
        Session staying = newSession();
        Candidate gone = seed(leaving);
        Candidate kept = seed(staying);
        UUID job = prepJob();
        stubPrep(gone.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        stubPrep(kept.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        generate(leaving, job).andExpect(status().isCreated());
        generate(staying, job).andExpect(status().isCreated());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/me")
                .header("Authorization", bearer(leaving))).andExpect(status().isNoContent());

        assertThat(preps(gone.userId())).isZero();
        assertThat(questionRows(gone.userId())).isZero();
        assertThat(briefRows(gone.userId())).isZero();
        assertThat(preps(kept.userId())).isEqualTo(1);
        assertThat(questionRows(kept.userId())).isEqualTo(9);
    }

    // --- helpers ---

    private void placeholder(UUID id, UUID userId, UUID job, String createdAt) {
        jdbc.update("""
                insert into interview_prep (id, user_id, job_id, job_title, job_company, status, prompt_version,
                        created_at, updated_at)
                values (?, ?, ?, 'Backend Engineer', 'Harbor Freight Tech', 'GENERATING', 'interview/v1',
                        %s, %s)
                """.formatted(createdAt, createdAt), id, userId, job);
    }

    /** $5 of AI today is far over the default cap of 500 credits ($0.50). */
    private void spendTheCap(UUID userId) {
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), userId, "parse_resume", "test", "m", 1, 1,
                new BigDecimal("5.00"), 1, "p/v1", "test", AiCallStatus.SUCCEEDED));
    }
}
