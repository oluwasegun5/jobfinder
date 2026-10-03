package com.jobfinder.core.interview;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.jayway.jsonpath.JsonPath;

/**
 * Starting a mock interview is idempotent per job (docs/adr/0034-mock-interview.md, addendum; migration V31): an open
 * session for the job is returned with 200, no model call and no charge; completed and abandoned sessions never block a
 * new start; concurrent starts end with one session.
 */
class MockInterviewIdempotentStartTests extends MockInterviewTestSupport {

    private static final String NEXT_1 = "Tell me about a project where you had to change your approach midway.";

    @Test
    void startingTwiceReturnsTheOpenSessionWithOneModelCallAndOneSetOfLedgerEntries() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));

        String first = body(start(me, job).andExpect(status().isCreated()));
        String id = JsonPath.read(first, "$.id");
        int lines = ledgerLines(user);
        BigDecimal credits = ledgerCredits(user);
        assertThat(callsOf(user, "mock_interview")).isEqualTo(1);

        String second = body(start(me, job).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.turns.length()").value(1))
                .andExpect(jsonPath("$.openQuestion.position").value(0)));
        assertThat(JsonPath.<String>read(second, "$.openQuestion.content"))
                .isEqualTo(JsonPath.<String>read(first, "$.openQuestion.content"));

        assertThat(sessionRows(user)).isEqualTo(1);
        assertThat(turnRows(id)).isEqualTo(1);
        assertThat(turnRequests(user)).hasSize(1);
        assertThat(callsOf(user, "mock_interview")).isEqualTo(1);
        assertThat(ledgerLines(user)).isEqualTo(lines);
        assertThat(ledgerCredits(user)).isEqualByComparingTo(credits).isEqualByComparingTo("4");
        assertThat(creditsConsumed(id)).isEqualByComparingTo("4");
    }

    @Test
    void aRepeatedStartResumesTheSessionInProgressWhateverTheSecondRequestAsks() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(startWith(me, job, Map.of("maxTurns", 3)).andExpect(status().isCreated()));
        stubTurn(user, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT_1));
        answer(me, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        int requests = turnRequests(user).size();

        // The resumed session keeps its progress and its own limit; the new request's maxTurns does not apply to it.
        startWith(me, job, Map.of("maxTurns", 5)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.maxTurns").value(3)).andExpect(jsonPath("$.turnsAnswered").value(1))
                .andExpect(jsonPath("$.openQuestion.position").value(2));

        assertThat(turnRequests(user)).hasSize(requests);
        assertThat(sessionRows(user)).isEqualTo(1);
    }

    @Test
    void aRepeatedStartIsFreeEvenWhenTheDailyCapIsReached() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, job).andExpect(status().isCreated()));
        spendTheCap(user);
        int calls = aiCalls(user);

        start(me, job).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(aiCalls(user)).isEqualTo(calls);
    }

    @Test
    void twoUsersStartingTheSameJobGetTwoSessions() throws Exception {
        Session alice = newSession();
        Session bob = newSession();
        UUID job = prepJob();
        stubTurn(userIdOf(alice), opening(UUID.randomUUID(), "0.004"));
        stubTurn(userIdOf(bob), opening(UUID.randomUUID(), "0.004"));

        String a = idOf(start(alice, job).andExpect(status().isCreated()));
        String b = idOf(start(bob, job).andExpect(status().isCreated()));

        assertThat(a).isNotEqualTo(b);
        assertThat(sessionRows(userIdOf(alice))).isEqualTo(1);
        assertThat(sessionRows(userIdOf(bob))).isEqualTo(1);
        // Each user's own repeat resumes their own session.
        start(alice, job).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(a));
        start(bob, job).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(b));
    }

    @Test
    void aCompletedSessionDoesNotBlockANewStart() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String first = idOf(startWith(me, job, Map.of("maxTurns", 1)).andExpect(status().isCreated()));
        stubTurn(user, lastTurn(UUID.randomUUID(), "0.003", "behavioral"));
        stubSummary(user, summary(UUID.randomUUID(), "0.002", 1));
        answer(me, first, GOOD_ANSWER, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.session.status").value("COMPLETED"));

        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String second = idOf(start(me, job).andExpect(status().isCreated()));

        assertThat(second).isNotEqualTo(first);
        assertThat(statusOf(first)).isEqualTo("COMPLETED");
        assertThat(statusOf(second)).isEqualTo("ACTIVE");
        assertThat(sessionRows(user)).isEqualTo(2);
    }

    @Test
    void anAbandonedSessionDoesNotBlockANewStartAndTheSweepRunsBeforeTheLookup() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String stale = idOf(start(me, job).andExpect(status().isCreated()));
        // Idle for two days but never read since: only the start's own sweep can notice that it is over.
        jdbc.update("update interview_sessions set last_activity_at = now() - interval '2 days' where id = ?::uuid",
                stale);

        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String fresh = idOf(start(me, job).andExpect(status().isCreated()));

        assertThat(fresh).isNotEqualTo(stale);
        assertThat(statusOf(stale)).isEqualTo("ABANDONED");
        assertThat(statusOf(fresh)).isEqualTo("ACTIVE");
    }

    @Test
    void differentJobsOfOneUserAreIndependent() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String a = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        String b = idOf(start(me, prepJob()).andExpect(status().isCreated()));
        assertThat(a).isNotEqualTo(b);
        assertThat(sessionRows(user)).isEqualTo(2);
    }

    @Test
    void theDatabaseAllowsOnlyOneActiveSessionPerUserAndJob() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        stubTurn(user, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(me, job).andExpect(status().isCreated()));

        // The index itself, below the service: a second ACTIVE row for the same user and job is refused,
        // while an ended one is not.
        String copy = "insert into interview_sessions (id, user_id, job_id, job_title, persona, status, max_turns, "
                + "prompt_version, last_activity_at, created_at, completed_at) select gen_random_uuid(), user_id, job_id, "
                + "job_title, persona, ?::varchar, max_turns, prompt_version, last_activity_at, created_at, "
                + "case when ?::varchar = 'COMPLETED' then now() end from interview_sessions where id = ?::uuid";
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update(copy, "ACTIVE", "ACTIVE", id));
        jdbc.update(copy, "COMPLETED", "COMPLETED", id);
        jdbc.update(copy, "ABANDONED", "ABANDONED", id);
        assertThat(sessionRows(user)).isEqualTo(3);
    }

    @Test
    void concurrentStartsEndWithOneSessionAndTheLoserGetsTheWinner() throws Exception {
        Session me = newSession();
        UUID user = userIdOf(me);
        UUID job = prepJob();
        // A slow first question, so both requests are past the lookup before either inserts.
        stubSlowTurn(user, opening(UUID.randomUUID(), "0.004"), 800);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<int[]> starter = () -> {
                var result = start(me, job).andReturn().getResponse();
                return new int[] { result.getStatus(), JsonPath.<String>read(result.getContentAsString(), "$.id").hashCode() };
            };
            List<Future<int[]>> both = pool.invokeAll(List.of(starter, starter));
            int[] one = both.get(0).get();
            int[] two = both.get(1).get();

            assertThat(List.of(one[0], two[0])).containsExactlyInAnyOrder(201, 200);
            assertThat(one[1]).isEqualTo(two[1]);
        } finally {
            pool.shutdownNow();
        }

        assertThat(sessionRows(user)).isEqualTo(1);
        String id = jdbc.queryForObject("select id::text from interview_sessions where user_id = ?", String.class, user);
        assertThat(turnRows(id)).isEqualTo(1);
        // The loser made its own model call before it lost the race (see the ADR addendum); it is not attributed to the
        // winner's session, which counts only its own call.
        assertThat(creditsConsumed(id)).isEqualByComparingTo("4");
    }
}
