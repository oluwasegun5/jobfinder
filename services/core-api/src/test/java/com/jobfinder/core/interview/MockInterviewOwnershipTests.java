package com.jobfinder.core.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Whose session it is. Every mock interview endpoint is scoped to the signed-in user: user B cannot read, answer or
 * complete user A's session, cannot tell it from one that does not exist, and a refused request never reaches the model.
 */
class MockInterviewOwnershipTests extends MockInterviewTestSupport {

    private static final String NEXT = "Tell me about a project where you had to change your approach midway.";

    @Test
    void everyEndpointRequiresASignedInUser() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/interview-sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + id + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/interview-sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/interview-sessions/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/interview-sessions/" + id + "/answers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"x\",\"idempotencyKey\":\"abcdefgh12\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/interview-sessions/" + id + "/complete")).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherUserCannotReadAnswerOrCompleteMySessionAndGetsTheAnswerForOneThatDoesNotExist() throws Exception {
        Session a = newSession();
        Session b = newSession();
        UUID mine = userIdOf(a);
        UUID theirs = userIdOf(b);
        stubTurn(mine, opening(UUID.randomUUID(), "0.004"));
        stubTurn(theirs, opening(UUID.randomUUID(), "0.004"));
        String id = idOf(start(a, prepJob()).andExpect(status().isCreated()));
        stubTurn(mine, turn(UUID.randomUUID(), "0.003", "behavioral", "behavioral", NEXT));
        answer(a, id, GOOD_ANSWER, key()).andExpect(status().isOk());
        stubSummary(mine, summary(UUID.randomUUID(), "0.002", 1));
        String missing = UUID.randomUUID().toString();
        int turnCalls = turnRequests(mine).size() + turnRequests(theirs).size();
        int turnRowsBefore = turnRows(id);
        BigDecimal creditsBefore = creditsConsumed(id);
        int linesBefore = ledgerLines(mine);

        String read = body(getSession(b, id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("interview_session_not_found")));
        String readMissing = body(getSession(b, missing).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("interview_session_not_found")));
        String answered = body(answer(b, id, GOOD_ANSWER, key()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("interview_session_not_found")));
        String completed = body(complete(b, id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("interview_session_not_found")));
        answer(b, missing, GOOD_ANSWER, key()).andExpect(status().isNotFound());
        complete(b, missing).andExpect(status().isNotFound());

        assertThat(read).doesNotContain("Harbor Freight Tech").doesNotContain("Backend Engineer")
                .doesNotContain(GOOD_ANSWER);
        assertThat(mapper.readTree(read).get("title")).isEqualTo(mapper.readTree(readMissing).get("title"));
        assertThat(mapper.readTree(answered).get("title")).isEqualTo(mapper.readTree(read).get("title"));
        assertThat(mapper.readTree(completed).get("title")).isEqualTo(mapper.readTree(read).get("title"));
        // Nothing reached the model, nothing was stored and nothing was charged to either user.
        assertThat(turnRequests(mine).size() + turnRequests(theirs).size()).isEqualTo(turnCalls);
        assertThat(summaryRequests(mine)).isEmpty();
        assertThat(turnRows(id)).isEqualTo(turnRowsBefore);
        assertThat(creditsConsumed(id)).isEqualByComparingTo(creditsBefore);
        assertThat(ledgerLines(mine)).isEqualTo(linesBefore);
        assertThat(ledgerLines(theirs)).isZero();
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        // The owner still can.
        getSession(a, id).andExpect(status().isOk());
        complete(a, id).andExpect(status().isOk());
    }

    @Test
    void theHistoryListsOnlyMySessions() throws Exception {
        Session a = newSession();
        Session b = newSession();
        stubTurn(userIdOf(a), opening(UUID.randomUUID(), "0.004"));
        stubTurn(userIdOf(b), opening(UUID.randomUUID(), "0.004"));
        UUID job = prepJob();
        String first = idOf(start(a, job).andExpect(status().isCreated()));
        String second = idOf(start(a, job).andExpect(status().isCreated()));
        String theirs = idOf(start(b, job).andExpect(status().isCreated()));

        String mine = body(listSessions(a, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2)));
        listSessions(b, "").andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(theirs));

        assertThat(mine).contains(first).contains(second).doesNotContain(theirs);
    }

    @Test
    void anotherUsersPrepCannotBeUsedToStartASession() throws Exception {
        Session a = newSession();
        Session b = newSession();
        Candidate owner = seed(a);
        seed(b);
        UUID job = prepJob();
        stubPrep(owner.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        String prepId = idOf(generate(a, job).andExpect(status().isCreated()));
        stubTurn(userIdOf(b), opening(UUID.randomUUID(), "0.004"));

        startWith(b, job, Map.of("prepId", prepId)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("interview_prep_not_found"));
        startWith(b, job, Map.of("prepId", UUID.randomUUID().toString())).andExpect(status().isNotFound());

        assertThat(sessionRows(userIdOf(b))).isZero();
        assertThat(turnRequests(userIdOf(b))).isEmpty();
    }
}
