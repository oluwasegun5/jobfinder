package com.jobfinder.core.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.node.ObjectNode;

/**
 * The mock interview flow (docs/adr/0034-mock-interview.md): start, answer, complete; what is metered and charged;
 * idempotent retries; the in-flight slot; the daily cap and the per-session limits; the session state machine; ai-service
 * failures and answers that are not what the contract promises. ai-service is WireMock serving the contract files its own
 * tests pin; no test talks to a model.
 */
@ExtendWith(OutputCaptureExtension.class)
class MockInterviewSessionTests extends MockInterviewTestSupport {

    private static final String NEXT_1 = "Tell me about a project where you had to change your approach midway.";
    private static final String NEXT_2 = "Tell me about a time you gave difficult feedback to a colleague.";

    // --- start ---

    @Test
    void aSessionStartsWithAModelQuestionAndThePersonaOfTheJob() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));

        String json = body(start(me, job).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.mode").value("MOCK"))
                .andExpect(jsonPath("$.jobId").value(job.toString()))
                .andExpect(jsonPath("$.jobTitle").value("Backend Engineer"))
                .andExpect(jsonPath("$.jobCompany").value("Harbor Freight Tech"))
                .andExpect(jsonPath("$.persona.interviewer").value("Engineering manager"))
                .andExpect(jsonPath("$.persona.function").value("engineering"))
                .andExpect(jsonPath("$.persona.seniority").value("senior"))
                .andExpect(jsonPath("$.persona.tone").value("direct"))
                .andExpect(jsonPath("$.persona.questionStyle").value("technical_depth"))
                .andExpect(jsonPath("$.maxTurns").value(8)).andExpect(jsonPath("$.turnsAnswered").value(0))
                .andExpect(jsonPath("$.promptVersion").value("mock_interview/v1"))
                .andExpect(jsonPath("$.openQuestion.position").value(0))
                .andExpect(jsonPath("$.openQuestion.role").value("INTERVIEWER"))
                .andExpect(jsonPath("$.openQuestion.source").value("GENERATED"))
                .andExpect(jsonPath("$.turns.length()").value(1)).andExpect(jsonPath("$.summary").doesNotExist()));

        // The first question cost one model call: 0.004 USD at 1000 micro-USD per credit is 4 credits.
        assertThat(new BigDecimal(JsonPath.read(json, "$.creditsConsumed").toString())).isEqualByComparingTo("4");
        assertThat(callsOf(user, "mock_interview")).isEqualTo(1);
        assertThat(ledgerCredits(user)).isEqualByComparingTo("4");
        List<LoggedRequest> sent = turnRequests(user);
        assertThat(sent).hasSize(1);
        String request = sent.get(0).getBodyAsString();
        assertThat(JsonPath.<String>read(request, "$.prompt_version")).isEqualTo("mock_interview/v1");
        assertThat(JsonPath.<String>read(request, "$.persona.tone")).isEqualTo("direct");
        assertThat(JsonPath.<String>read(request, "$.job.title")).isEqualTo("Backend Engineer");
        assertThat(request).doesNotContain("\"answer\"");
        assertThat(JsonPath.<Boolean>read(request, "$.need_next")).isTrue();
    }

    @Test
    void aSessionFromAPrepTakesItsFirstQuestionFromItWithNoModelCallAndNoCapCheck() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        String prepId = idOf(generate(me, job).andExpect(status().isCreated()));
        spendTheCap(candidate.userId());
        int callsBefore = aiCalls(candidate.userId());

        String json = body(startWith(me, job, Map.of("prepId", prepId)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.prepId").value(prepId))
                .andExpect(jsonPath("$.openQuestion.content")
                        .value("Tell me about a time you led a team through a hard deadline."))
                .andExpect(jsonPath("$.openQuestion.category").value("behavioral"))
                .andExpect(jsonPath("$.openQuestion.source").value("PREP"))
                .andExpect(jsonPath("$.creditsConsumed").value(0)));

        assertThat(turnRequests(candidate.userId())).isEmpty();
        assertThat(aiCalls(candidate.userId())).isEqualTo(callsBefore);
        assertThat(turnRows(JsonPath.read(json, "$.id"))).isEqualTo(1);
    }

    @Test
    void aSessionCanAskForFewerTurnsButNotMoreThanTheLimit() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));

        startWith(me, job, Map.of("maxTurns", 2)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxTurns").value(2));
        startWith(me, job, Map.of("maxTurns", 9)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_max_turns"));
        startWith(me, job, Map.of("maxTurns", 0)).andExpect(status().isBadRequest());
        startWith(me, job, Map.of("maxTurns", 21)).andExpect(status().isBadRequest());

        assertThat(sessionRows(user)).isEqualTo(1);
    }

    @Test
    void startingNeedsAKnownJobAndAJobId() throws Exception {
        Session me = newSession();

        start(me, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
        start(me, "{}").andExpect(status().isBadRequest());
        assertThat(sessionRows(userIdOf(me))).isZero();
    }

    @Test
    void aSessionCanBeTiedToTheUsersOwnApplicationForTheSameJobButNotAnotherJobsOrAStrangers() throws Exception {
        Session me = newSession();
        Session other = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        UUID otherJob = newJob();
        UUID mine = application(user, job);
        UUID forAnotherJob = application(user, otherJob);
        UUID theirs = application(userIdOf(other), job);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));

        startWith(me, job, Map.of("applicationId", mine.toString())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationId").value(mine.toString()));
        startWith(me, job, Map.of("applicationId", forAnotherJob.toString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("application_job_mismatch"));
        startWith(me, job, Map.of("applicationId", theirs.toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("application_not_found"));
        startWith(me, job, Map.of("applicationId", UUID.randomUUID().toString())).andExpect(status().isNotFound());

        assertThat(sessionRows(user)).isEqualTo(1);
    }

    @Test
    void aPrepOfAnotherJobIsRefused() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        UUID other = newJob();
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        String prepId = idOf(generate(me, job).andExpect(status().isCreated()));

        startWith(me, other, Map.of("prepId", prepId)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("prep_job_mismatch"));
    }

    // --- a whole session, and what it costs ---

    @Test
    void aFullSessionGivesFeedbackAfterEveryAnswerAndASummaryAtTheEndAndTheCreditsAddUp() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(startWith(me, job, Map.of("maxTurns", 3)).andExpect(status().isCreated()));

        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        String first = body(answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.position").value(1)).andExpect(jsonPath("$.turn.role").value("CANDIDATE"))
                .andExpect(jsonPath("$.turn.feedback.overall").value(4))
                .andExpect(jsonPath("$.turn.feedback.structure").value(4))
                .andExpect(jsonPath("$.turn.feedback.star.score").value(5))
                .andExpect(jsonPath("$.turn.feedback.strengths[0].quote").isString())
                .andExpect(jsonPath("$.nextQuestion.position").value(2))
                .andExpect(jsonPath("$.nextQuestion.content").value(NEXT_1))
                .andExpect(jsonPath("$.session.turnsAnswered").value(1))
                .andExpect(jsonPath("$.session.openQuestion.content").value(NEXT_1))
                .andExpect(jsonPath("$.summary").doesNotExist()));
        assertThat(new BigDecimal(JsonPath.read(first, "$.session.creditsConsumed").toString()))
                .isEqualByComparingTo("7");

        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_2));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextQuestion.content").value(NEXT_2))
                .andExpect(jsonPath("$.session.turnsAnswered").value(2));

        stubTurn(user, lastTurn(UUID.randomUUID(), "0.003", "behavioral"));
        stubSummary(user, summary(UUID.randomUUID(), "0.002", 3));
        String last = body(answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextQuestion").doesNotExist())
                .andExpect(jsonPath("$.session.status").value("COMPLETED"))
                .andExpect(jsonPath("$.session.openQuestion").doesNotExist())
                .andExpect(jsonPath("$.session.completedAt").isString())
                .andExpect(jsonPath("$.summary.turnsAnswered").value(3))
                .andExpect(jsonPath("$.summary.averages.overall").value(4.0))
                .andExpect(jsonPath("$.summary.averages.structure").value(4.0))
                .andExpect(jsonPath("$.summary.averages.relevance").value(5.0))
                .andExpect(jsonPath("$.summary.averages.specificity").value(4.0))
                .andExpect(jsonPath("$.summary.averages.starCompleteness").value(5.0))
                .andExpect(jsonPath("$.summary.topStrengths.length()").value(2))
                .andExpect(jsonPath("$.summary.nextSteps.length()").value(3))
                .andExpect(jsonPath("$.summary.narrative").isString()));

        // 4 + 3 + 3 + 3 for the opening and three turns, 2 for the summary.
        assertThat(new BigDecimal(JsonPath.read(last, "$.summary.creditsConsumed").toString()))
                .isEqualByComparingTo("15");
        assertThat(creditsConsumed(id)).isEqualByComparingTo("15");
        assertThat(ledgerCredits(user)).isEqualByComparingTo("15");
        assertThat(callsOf(user, "mock_interview")).isEqualTo(4);
        assertThat(callsOf(user, "mock_interview_summary")).isEqualTo(1);
        assertThat(turnRows(id)).isEqualTo(6);
        getSession(me, id).andExpect(status().isOk()).andExpect(jsonPath("$.turns.length()").value(6))
                .andExpect(jsonPath("$.turns[5].feedback.overall").value(4));
    }

    @Test
    void theNextQuestionOfASessionFromAPrepComesFromTheServiceAndTheRemainingPrepIsSent() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = prepJob();
        stubPrep(candidate.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        String prepId = idOf(generate(me, job).andExpect(status().isCreated()));
        String id = idOf(startWith(me, job, Map.of("prepId", prepId)).andExpect(status().isCreated()));
        ObjectNode reply = turn(UUID.randomUUID(), "0.003", "behavioral", "technical",
                "How have you used Kafka to move batch work to consumers?");
        ((ObjectNode) reply.get("next_question")).put("source", "prep");
        stubTurn(candidate.userId(), reply);

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextQuestion.source").value("PREP"))
                .andExpect(jsonPath("$.nextQuestion.category").value("technical"));

        String request = turnRequests(candidate.userId()).get(0).getBodyAsString();
        // The question already asked is not offered again; the other eight are.
        assertThat(JsonPath.<List<String>>read(request, "$.prep_questions[*].question")).hasSize(8)
                .doesNotContain("Tell me about a time you led a team through a hard deadline.");
        assertThat(JsonPath.<List<String>>read(request, "$.asked")).containsExactly(
                "Tell me about a time you led a team through a hard deadline.");
        assertThat(JsonPath.<Boolean>read(request, "$.need_next")).isTrue();
        assertThat(JsonPath.<String>read(request, "$.answer.category")).isEqualTo("behavioral");
        assertThat(JsonPath.<String>read(request, "$.answer.text")).isEqualTo(GOOD_ANSWER);
    }

    @Test
    void theSessionsCreditsAreNeverMoreThanTheLedgerSaysEvenWhenACallIsBilledAndDiscarded() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, job).andExpect(status().isCreated()));
        UUID billed = UUID.randomUUID();
        stubTurn(user, 502, problem(billed, "0.003"));

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("mock_interview_unavailable"));

        assertThat(callStatus(billed)).isEqualTo("FAILED");
        assertThat(creditsConsumed(id)).isEqualByComparingTo("7");
        assertThat(ledgerCredits(user)).isEqualByComparingTo("7");
        assertThat(turnRows(id)).isEqualTo(1);
    }

    // --- ai-service ran out of its overall deadline ---

    @Test
    void aDeadlineErrorOnAnAnswerRecordsTheCallsBilledBeforeItInTheLedgerAndTheSessionAndIsThe503() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        UUID billed = UUID.randomUUID();
        // The first attempt completed and was billed, the retry was cancelled at the deadline.
        stubTurn(user, 504, deadlineProblem(billed, "0.003", "mock_interview"));

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("mock_interview_unavailable"));

        assertThat(callStatus(billed)).isEqualTo("FAILED");
        assertThat(creditsConsumed(id)).isEqualByComparingTo("7");
        assertThat(ledgerCredits(user)).isEqualByComparingTo("7");
        assertThat(turnRows(id)).isEqualTo(1);
        assertThat(slotTaken(id)).isFalse();
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
    }

    @Test
    void aDeadlineErrorWithNothingBilledChargesNothingAndLeavesTheSessionRetryable() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        stubTurn(user, 504, "{\"code\":\"llm_deadline_exceeded\",\"retryable\":true,\"usage\":[]}");
        String key = key();

        answer(me, id, GOOD_ANSWER, key).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("mock_interview_unavailable"));

        assertThat(ledgerCredits(user)).isEqualByComparingTo("4");
        assertThat(creditsConsumed(id)).isEqualByComparingTo("4");
        assertThat(slotTaken(id)).isFalse();
        // The same key can be sent again once ai-service answers in time.
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        answer(me, id, GOOD_ANSWER, key).andExpect(status().isOk());
    }

    @Test
    void aDeadlineErrorOnTheFirstQuestionCreatesNoSessionButRecordsWhatWasBilled() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID billed = UUID.randomUUID();
        stubTurn(user, 504, deadlineProblem(billed, "0.003", "mock_interview"));

        start(me, prepJob()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("mock_interview_unavailable"));

        assertThat(sessionRows(user)).isZero();
        assertThat(callStatus(billed)).isEqualTo("FAILED");
        assertThat(ledgerCredits(user)).isEqualByComparingTo("3");
    }

    @Test
    void aDeadlineErrorOnTheSummaryRecordsItsUsageAndLeavesTheSessionActive() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(startWith(me, prepJob(), Map.of("maxTurns", 3)).andExpect(status().isCreated()));
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        UUID billed = UUID.randomUUID();
        stubSummary(user, 504, deadlineProblem(billed, "0.002", "mock_interview_summary"));

        complete(me, id).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("mock_interview_unavailable"));

        assertThat(callStatus(billed)).isEqualTo("FAILED");
        assertThat(creditsConsumed(id)).isEqualByComparingTo("9");
        assertThat(ledgerCredits(user)).isEqualByComparingTo("9");
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(slotTaken(id)).isFalse();
    }

    // --- idempotency ---

    @Test
    void aRepeatedSubmissionReturnsTheStoredResultWithNoModelCallAndNoExtraCharge() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, job).andExpect(status().isCreated()));
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        String key = key();
        String first = body(answer(me, id, GOOD_ANSWER, key).andExpect(status().isOk()));
        int requests = turnRequests(user).size();
        int calls = callsOf(user, "mock_interview");
        int lines = ledgerLines(user);
        int turns = turnRows(id);
        BigDecimal credits = creditsConsumed(id);

        // Another answer moves the session on; the first key still returns the first result.
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_2));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        int requestsAfterSecond = turnRequests(user).size();
        int linesAfterSecond = ledgerLines(user);
        BigDecimal creditsAfterSecond = creditsConsumed(id);
        String repeated = body(answer(me, id, GOOD_ANSWER, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.position").value(1)).andExpect(jsonPath("$.nextQuestion.content")
                        .value(NEXT_1)));

        assertThat(requests).isEqualTo(2);
        assertThat(requestsAfterSecond).isEqualTo(3);
        assertThat(turnRequests(user)).hasSize(requestsAfterSecond);
        assertThat(callsOf(user, "mock_interview")).isEqualTo(calls + 1);
        assertThat(ledgerLines(user)).isEqualTo(linesAfterSecond).isEqualTo(lines + 1);
        assertThat(creditsConsumed(id)).isEqualByComparingTo(creditsAfterSecond)
                .isEqualByComparingTo(credits.add(new BigDecimal("3")));
        assertThat(turnRows(id)).isEqualTo(turns + 2);
        assertThat(mapper.readTree(repeated).get("turn")).isEqualTo(mapper.readTree(first).get("turn"));
        assertThat(mapper.readTree(repeated).get("nextQuestion")).isEqualTo(mapper.readTree(first).get("nextQuestion"));
    }

    @Test
    void aRepeatRightAfterTheFirstReturnsTheSameBodyAndChargesOnce() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, job).andExpect(status().isCreated()));
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        String key = key();

        String first = body(answer(me, id, GOOD_ANSWER, key).andExpect(status().isOk()));
        int lines = ledgerLines(user);
        String second = body(answer(me, id, GOOD_ANSWER, key).andExpect(status().isOk()));

        assertThat(mapper.readTree(second)).isEqualTo(mapper.readTree(first));
        assertThat(turnRequests(user)).hasSize(2);
        assertThat(ledgerLines(user)).isEqualTo(lines);
        assertThat(callsOf(user, "mock_interview")).isEqualTo(2);
        assertThat(creditsConsumed(id)).isEqualByComparingTo("7");
    }

    @Test
    void aKeyUsedForADifferentAnswerIsAConflictAndCallsNoModel() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        String key = key();
        answer(me, id, GOOD_ANSWER, key).andExpect(status().isOk());

        answer(me, id, GOOD_ANSWER + " And more.", key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));

        assertThat(turnRequests(user)).hasSize(2);
    }

    @Test
    void aMalformedKeyIsABadRequest() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));

        answer(me, id, GOOD_ANSWER, "short").andExpect(status().isBadRequest());
        answer(me, id, GOOD_ANSWER, "has spaces in the key").andExpect(status().isBadRequest());
        answer(me, id, GOOD_ANSWER, "x".repeat(101)).andExpect(status().isBadRequest());
        assertThat(turnRequests(user)).hasSize(1);
    }

    // --- one answer in flight ---

    @Test
    void aSecondConcurrentAnswerGetsAConflictAndOnlyOneModelCallIsMade() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        stubSlowTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1), 1500);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> slow = pool.submit(statusOf(me, id, key()));
            Thread.sleep(400);
            ResultActions blocked = answer(me, id, GOOD_ANSWER, key());
            blocked.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("answer_in_flight"));
            assertThat(slow.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(turnRequests(user)).hasSize(2);
        assertThat(turnRows(id)).isEqualTo(3);
        assertThat(slotTaken(id)).isFalse();
        // The slot is free again: the next answer is accepted.
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_2));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
    }

    @Test
    void aSlotLeftByADeadRequestIsTakenOverAfterTheTimeout() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        jdbc.update("update interview_sessions set in_flight_since = now(), in_flight_key = 'dead' "
                + "where id = ?::uuid", id);
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("answer_in_flight"));
        jdbc.update("update interview_sessions set in_flight_since = now() - interval '10 minutes' "
                + "where id = ?::uuid", id);
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
    }

    private Callable<Integer> statusOf(Session me, String id, String key) {
        return () -> answer(me, id, GOOD_ANSWER, key).andReturn().getResponse().getStatus();
    }

    // --- cap and limits ---

    @Test
    void anExhaustedDailyCapBlocksAnAnswerBeforeAnyCallAndLeavesNothingBehind() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        spendTheCap(user);
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ai_daily_cap_reached")).andExpect(jsonPath("$.resetsAt").isString())
                .andExpect(header().exists("Retry-After"));

        assertThat(turnRequests(user)).hasSize(1);
        assertThat(turnRows(id)).isEqualTo(1);
        assertThat(slotTaken(id)).isFalse();
        assertThat(creditsConsumed(id)).isEqualByComparingTo("4");
    }

    @Test
    void anExhaustedDailyCapBlocksStartingWithoutAPrepAndCreatesNoSession() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        spendTheCap(user);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));

        start(me, prepJob()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ai_daily_cap_reached"));

        assertThat(turnRequests(user)).isEmpty();
        assertThat(sessionRows(user)).isZero();
    }

    @Test
    void anAnswerLongerThanTheLimitIsRefusedBeforeAnyCall() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));

        answer(me, id, "a".repeat(4001), key()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("answer_too_long"));
        answer(me, id, "   ", key()).andExpect(status().isBadRequest());
        answer(me, id, "", key()).andExpect(status().isBadRequest());

        assertThat(turnRequests(user)).hasSize(1);
        assertThat(turnRows(id)).isEqualTo(1);
        // The limit itself is allowed.
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        String atTheLimit = GOOD_ANSWER + " " + "a".repeat(4000 - GOOD_ANSWER.length() - 1);
        assertThat(atTheLimit).hasSize(4000);
        answer(me, id, atTheLimit, key()).andExpect(status().isOk());
    }

    // --- state machine ---

    @Test
    void noAnswerIsAcceptedAfterTheLastTurnAndCompleteIsIdempotent() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(startWith(me, prepJob(), Map.of("maxTurns", 1)).andExpect(status().isCreated()));
        stubTurn(user, lastTurn(UUID.randomUUID(), "0.003", "behavioral"));
        stubSummary(user, summary(UUID.randomUUID(), "0.002", 1));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.session.status").value("COMPLETED"));
        int turnCalls = turnRequests(user).size();

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("interview_session_completed"));
        String once = body(complete(me, id).andExpect(status().isOk()).andExpect(jsonPath("$.status")
                .value("COMPLETED")));
        String twice = body(complete(me, id).andExpect(status().isOk()));

        assertThat(mapper.readTree(twice)).isEqualTo(mapper.readTree(once));
        assertThat(turnRequests(user)).hasSize(turnCalls);
        assertThat(summaryRequests(user)).hasSize(1);
        assertThat(callsOf(user, "mock_interview_summary")).isEqualTo(1);
        // The opening question and the one answer: no question follows the last answer.
        assertThat(turnRows(id)).isEqualTo(2);
    }

    @Test
    void aSessionCanBeEndedEarlyAndItsSummaryIsMadeFromWhatWasAnswered() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        stubSummary(user, summary(UUID.randomUUID(), "0.002", 1));

        complete(me, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.openQuestion").doesNotExist())
                .andExpect(jsonPath("$.summary.turnsAnswered").value(1))
                .andExpect(jsonPath("$.creditsConsumed").value(9.0));

        String request = summaryRequests(user).get(0).getBodyAsString();
        assertThat(JsonPath.<Boolean>read(request, "$.ended_early")).isTrue();
        assertThat(JsonPath.<List<Object>>read(request, "$.turns")).hasSize(1);
        assertThat(JsonPath.<String>read(request, "$.turns[0].question"))
                .isNotBlank();
        // The summary is made from the stored feedback, not from the candidate's answers.
        assertThat(request).doesNotContain("Northwind");
        assertThat(ledgerCredits(user)).isEqualByComparingTo("9");
    }

    @Test
    void completingASessionWithNoAnswerIsAConflict() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));

        complete(me, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("nothing_to_summarise"));

        assertThat(summaryRequests(user)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
    }

    @Test
    void anIdleSessionIsAbandonedAndCannotBeResumedCompletedOrAnswered() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        jdbc.update("update interview_sessions set last_activity_at = now() - interval '2 days' where id = ?::uuid",
                id);
        int calls = turnRequests(user).size();

        getSession(me, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ABANDONED"))
                .andExpect(jsonPath("$.openQuestion").doesNotExist())
                .andExpect(jsonPath("$.turns.length()").value(3));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("interview_session_abandoned"));
        complete(me, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("interview_session_abandoned"));

        assertThat(turnRequests(user)).hasSize(calls);
        assertThat(summaryRequests(user)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("ABANDONED");
        listSessions(me, "").andExpect(jsonPath("$.items[0].status").value("ABANDONED"));
    }

    @Test
    void anAnswerKeepsASessionAliveButASessionBeingWorkedOnIsNotAbandoned() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        jdbc.update("update interview_sessions set last_activity_at = now() - interval '23 hours' where id = ?::uuid",
                id);
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        getSession(me, id).andExpect(jsonPath("$.status").value("ACTIVE"));

        jdbc.update("update interview_sessions set last_activity_at = now() - interval '2 days', "
                + "in_flight_since = now(), in_flight_key = 'busy' where id = ?::uuid", id);
        getSession(me, id).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aFailedSummaryAfterTheLastAnswerLeavesTheSessionActiveAndCompleteRetriesIt() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(startWith(me, prepJob(), Map.of("maxTurns", 1)).andExpect(status().isCreated()));
        stubTurn(user, lastTurn(UUID.randomUUID(), "0.003", "behavioral"));
        UUID failedSummary = UUID.randomUUID();
        stubSummary(user, 502, problem(failedSummary, "0.002", "mock_interview_summary"));

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.feedback.overall").value(4))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.session.status").value("ACTIVE"))
                .andExpect(jsonPath("$.session.turnsAnswered").value(1))
                .andExpect(jsonPath("$.session.openQuestion").doesNotExist());

        assertThat(callStatus(failedSummary)).isEqualTo("FAILED");
        assertThat(slotTaken(id)).isFalse();
        assertThat(creditsConsumed(id)).isEqualByComparingTo("9");
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("turn_limit_reached"));

        stubSummary(user, summary(UUID.randomUUID(), "0.002", 1));
        complete(me, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary.turnsAnswered").value(1));
        assertThat(creditsConsumed(id)).isEqualByComparingTo("11");
        assertThat(ledgerCredits(user)).isEqualByComparingTo("11");
    }

    @Test
    void aSummaryTheCapBlocksAfterTheLastAnswerWaitsForComplete() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(startWith(me, prepJob(), Map.of("maxTurns", 1)).andExpect(status().isCreated()));
        // The turn itself costs enough to reach the cap.
        stubTurn(user, lastTurn(UUID.randomUUID(), "0.600", "behavioral"));

        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.session.status").value("ACTIVE")).andExpect(jsonPath("$.summary")
                        .doesNotExist());

        assertThat(summaryRequests(user)).isEmpty();
        assertThat(slotTaken(id)).isFalse();
        complete(me, id).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ai_daily_cap_reached"));
        assertThat(summaryRequests(user)).isEmpty();
    }

    // --- ai-service failures and answers that are not the contract ---

    @Test
    void whenAiServiceIsDownTheAnswerIs503NothingIsStoredAndTheSlotIsFree() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        stubTurn(user, 503, "{\"code\":\"llm_unavailable\"}");
        String key = key();

        answer(me, id, GOOD_ANSWER, key).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("mock_interview_unavailable"));

        assertThat(turnRows(id)).isEqualTo(1);
        assertThat(slotTaken(id)).isFalse();
        // The same key works once the service is back: nothing was stored under it.
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        answer(me, id, GOOD_ANSWER, key).andExpect(status().isOk());
    }

    @Test
    void anAnswerThatIsNotWhatTheContractPromisesIsDiscardedAndItsUsageIsRecordedAsFailed() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        int checked = 0;
        for (var bad : badTurns()) {
            UUID call = UUID.randomUUID();
            ObjectNode body = turn(call, "0.003", "behavioral", "behavioral", NEXT_1);
            bad.mutate().accept(body);
            stubTurn(user, body);

            answer(me, id, GOOD_ANSWER, key()).andExpect(status().isServiceUnavailable());

            assertThat(callStatus(call)).as(bad.name()).isEqualTo("FAILED");
            assertThat(turnRows(id)).as(bad.name()).isEqualTo(1);
            assertThat(slotTaken(id)).as(bad.name()).isFalse();
            checked++;
        }
        assertThat(checked).isEqualTo(badTurns().size());
        assertThat(creditsConsumed(id)).isEqualByComparingTo(new BigDecimal("4").add(new BigDecimal("3")
                .multiply(BigDecimal.valueOf(checked))));
        assertThat(ledgerCredits(user)).isEqualByComparingTo(creditsConsumed(id));
    }

    private record Bad(String name, java.util.function.Consumer<ObjectNode> mutate) {
    }

    private List<Bad> badTurns() {
        return List.of(
                new Bad("score above 5", b -> ((ObjectNode) b.get("feedback")).put("overall", 6)),
                new Bad("score below 1", b -> ((ObjectNode) b.get("feedback")).put("structure", 0)),
                new Bad("score is a decimal", b -> ((ObjectNode) b.get("feedback")).put("relevance", 3.5)),
                new Bad("score is a string", b -> ((ObjectNode) b.get("feedback")).put("specificity", "4")),
                new Bad("score missing", b -> ((ObjectNode) b.get("feedback")).remove("overall")),
                new Bad("star missing", b -> ((ObjectNode) b.get("feedback")).remove("star")),
                new Bad("star incomplete for a behavioural question",
                        b -> ((ObjectNode) b.get("feedback").get("star")).remove("result")),
                new Bad("star score above its components", b -> {
                    ObjectNode star = (ObjectNode) b.get("feedback").get("star");
                    star.put("task", false).put("action", false).put("result", false).put("score", 5);
                }),
                new Bad("strength without a quote",
                        b -> ((ObjectNode) b.get("feedback").get("strengths").get(0)).putNull("quote")),
                new Bad("strength quote not in the answer",
                        b -> ((ObjectNode) b.get("feedback").get("strengths").get(0)).put("quote",
                                "I led the migration to Kubernetes")),
                new Bad("evidence not in the answer",
                        b -> ((tools.jackson.databind.node.ArrayNode) b.get("feedback").get("evidence"))
                                .add("words the candidate never wrote")),
                new Bad("no improvements",
                        b -> ((ObjectNode) b.get("feedback")).putArray("improvements")),
                new Bad("a placeholder in a point",
                        b -> ((ObjectNode) b.get("feedback").get("strengths").get(0)).put("text",
                                "You did well at [Company].")),
                new Bad("next question repeats an earlier one",
                        b -> ((ObjectNode) b.get("next_question")).put("question",
                                "  " + "Describe a time you had to learn a new tool quickly".toUpperCase() + "!")),
                new Bad("next question says prep but is not from the prep",
                        b -> ((ObjectNode) b.get("next_question")).put("source", "prep")),
                new Bad("next question missing", b -> b.remove("next_question")),
                new Bad("next question has an unknown category",
                        b -> ((ObjectNode) b.get("next_question")).put("category", "trivia")),
                new Bad("another prompt version", b -> b.put("prompt_version", "mock_interview/v9")));
    }

    @Test
    void aNonBehaviouralQuestionNeedsANullStarAndABehaviouralOneAFullStar() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        // The open question is behavioural, so a feedback with a null star is refused.
        ObjectNode nullStar = turn(UUID.randomUUID(), "0.003", "technical", "behavioral", NEXT_1);
        stubTurn(user, nullStar);
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isServiceUnavailable());

        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "technical",
                "How would you design a retry policy for a flaky downstream service?"));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        // Now the open question is technical: a full star is refused, a null one accepted.
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_2));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isServiceUnavailable());
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "technical", "behavioral", NEXT_2));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.feedback.star.score").doesNotExist());
    }

    @Test
    void aSummaryWhoseAveragesDisagreeWithTheStoredFeedbackIsDiscarded() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        UUID call = UUID.randomUUID();
        ObjectNode lying = summary(call, "0.002", 1);
        ((ObjectNode) lying.get("averages")).put("overall", 5.0);
        stubSummary(user, lying);

        complete(me, id).andExpect(status().isServiceUnavailable());

        assertThat(callStatus(call)).isEqualTo("FAILED");
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(slotTaken(id)).isFalse();
        // A top strength that is not in the stored feedback is discarded too.
        ObjectNode invented = summary(UUID.randomUUID(), "0.002", 1);
        ((tools.jackson.databind.node.ArrayNode) invented.get("top_strengths")).add("You have ten years of Go.");
        stubSummary(user, invented);
        complete(me, id).andExpect(status().isServiceUnavailable());
        stubSummary(user, summary(UUID.randomUUID(), "0.002", 1));
        complete(me, id).andExpect(status().isOk());
    }

    // --- prompt injection ---

    @Test
    void anInstructionInTheAnswerDoesNotRaiseTheScoresOrBecomeExperienceInTheStoredFeedback() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        // What ai-service returned for this answer in its own pinned test: the model obeyed, the code dropped its claims
        // and capped the scores.
        ObjectNode pinned = fixture("/ai-service/mock-turn-injected.json");
        ((tools.jackson.databind.node.ArrayNode) pinned.get("usage")).forEach(u -> ((ObjectNode) u)
                .put("call_id", UUID.randomUUID().toString()).put("cost_usd", "0.003"));
        pinned.putObject("next_question").put("category", "behavioral").put("question", NEXT_1).put("source",
                "generated");
        stubTurn(user, pinned);
        String answer = GOOD_ANSWER + " " + INJECTION;

        String json = body(answer(me, id, answer, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.content").value(answer)));

        assertThat(JsonPath.<Integer>read(json, "$.turn.feedback.overall")).isLessThanOrEqualTo(3);
        assertThat(JsonPath.<Integer>read(json, "$.turn.feedback.structure")).isLessThanOrEqualTo(3);
        assertThat(JsonPath.<Integer>read(json, "$.turn.feedback.specificity")).isLessThanOrEqualTo(3);
        String stored = feedbackText(id).toLowerCase();
        assertThat(stored).doesNotContain("10 years").doesNotContain("go experience").doesNotContain("5/5")
                .doesNotContain("decade");
        assertThat(JsonPath.<List<Object>>read(json, "$.turn.feedback.strengths")).isEmpty();
        // The answer was passed on as the candidate's own text: ai-service is where instruction-like sentences are removed.
        assertThat(JsonPath.<String>read(turnRequests(user).get(1).getBodyAsString(), "$.answer.text"))
                .isEqualTo(answer);
    }

    // --- logs ---

    @Test
    void noAnswerIsEverLogged(CapturedOutput output) throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        String marker = "zebra-marker-" + UUID.randomUUID();
        String answer = GOOD_ANSWER + " " + marker;

        stubTurn(user, 503, "{\"code\":\"llm_unavailable\"}");
        answer(me, id, answer, key()).andExpect(status().isServiceUnavailable());
        ObjectNode bad = turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1);
        ((ObjectNode) bad.get("feedback")).put("overall", 9);
        stubTurn(user, bad);
        answer(me, id, answer, key()).andExpect(status().isServiceUnavailable());
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        answer(me, id, answer, key()).andExpect(status().isOk());
        answer(me, id, "a".repeat(5000) + marker, key()).andExpect(status().isBadRequest());

        assertThat(output.getAll()).doesNotContain(marker);
    }

    // --- helpers ---

    /** An ai-service problem document that lists one billed call, as an error body does. */
    /** What ai-service answers (504) when its overall deadline ran out after a call had completed and been billed. */
    private String deadlineProblem(UUID call, String costUsd, String feature) {
        return problem(call, costUsd, feature).replace("\"code\":\"llm_output_invalid\",\"retryable\":false",
                "\"code\":\"llm_deadline_exceeded\",\"retryable\":true");
    }

    private String problem(UUID call, String costUsd) {
        return problem(call, costUsd, "mock_interview");
    }

    private String problem(UUID call, String costUsd, String feature) {
        return "{\"code\":\"llm_output_invalid\",\"retryable\":false,\"usage\":[{\"user_id\":\"" + UUID.randomUUID()
                + "\",\"feature\":\"" + feature + "\",\"provider\":\"fake\",\"model\":\"fake-strong\","
                + "\"input_tokens\":10,\"output_tokens\":10,\"cost_usd\":\"" + costUsd + "\",\"latency_ms\":1,"
                + "\"prompt_version\":\"mock_interview/v1\",\"call_id\":\"" + call + "\",\"pricing_version\":\"t\"}]}";
    }

    private UUID application(UUID userId, UUID jobId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, user_id, job_id, title, company, status, status_changed_at, created_at,
                        updated_at)
                values (?, ?, ?, 'Backend Engineer', 'Harbor Freight Tech', 'SAVED', now(), now(), now())
                """, id, userId, jobId);
        return id;
    }
}
