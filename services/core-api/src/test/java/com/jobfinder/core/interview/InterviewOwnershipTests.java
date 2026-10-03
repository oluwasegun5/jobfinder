package com.jobfinder.core.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Whose prep it is. Every endpoint is scoped to the signed-in user: user B cannot read user A's prep, and cannot tell
 * a prep that exists from one that does not.
 */
class InterviewOwnershipTests extends InterviewTestSupport {

    @Test
    void readingRequiresASignedInUser() throws Exception {
        mvc.perform(get("/interview-prep/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherUserCannotReadMyPrepAndGetsTheSameAnswerAsForOneThatDoesNotExist() throws Exception {
        Session a = newSession();
        Session b = newSession();
        Candidate mine = seed(a);
        seed(b);
        stubPrep(mine.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        String id = idOf(generate(a, prepJob()).andExpect(status().isCreated()));

        getPrep(a, id).andExpect(status().isOk());
        String theirs = body(getPrep(b, id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("interview_prep_not_found")));
        String missing = body(getPrep(b, UUID.randomUUID().toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("interview_prep_not_found")));

        assertThat(theirs).doesNotContain("Harbor Freight Tech").doesNotContain("Backend Engineer");
        assertThat(mapper.readTree(theirs).get("title")).isEqualTo(mapper.readTree(missing).get("title"));
    }

    @Test
    void generatingForTheSameJobGivesEachUserTheirOwnPrepAndNeverSharesOne() throws Exception {
        Session a = newSession();
        Session b = newSession();
        Candidate first = seed(a);
        Candidate second = seed(b);
        UUID job = prepJob();
        stubPrep(first.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));
        stubPrep(second.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));

        String idA = idOf(generate(a, job).andExpect(status().isCreated()));
        String idB = idOf(generate(b, job).andExpect(status().isCreated()));

        assertThat(idA).isNotEqualTo(idB);
        assertThat(prepRequests(first.userId())).hasSize(1);
        assertThat(prepRequests(second.userId())).hasSize(1);
        getPrep(a, idA).andExpect(status().isOk());
        getPrep(b, idB).andExpect(status().isOk());
        getPrep(a, idB).andExpect(status().isNotFound());
        getPrep(b, idA).andExpect(status().isNotFound());
        assertThat(preps(first.userId())).isEqualTo(1);
        assertThat(preps(second.userId())).isEqualTo(1);
    }

    @Test
    void aUserCannotUseAnotherUsersGeneratingRowToSkipTheirOwnGeneration() throws Exception {
        Session a = newSession();
        Session b = newSession();
        Candidate mine = seed(a);
        Candidate theirs = seed(b);
        UUID job = prepJob();
        UUID running = UUID.randomUUID();
        jdbc.update("""
                insert into interview_prep (id, user_id, job_id, job_title, job_company, status, prompt_version,
                        created_at, updated_at)
                values (?, ?, ?, 'Backend Engineer', 'Harbor Freight Tech', 'GENERATING', 'interview/v1', now(), now())
                """, running, mine.userId(), job);
        stubPrep(theirs.userId(), prepOk(UUID.randomUUID(), UUID.randomUUID(), "0.003"));

        generate(b, job).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"));

        assertThat(prepRequests(theirs.userId())).hasSize(1);
        assertThat(prepRequests(mine.userId())).isEmpty();
    }
}
