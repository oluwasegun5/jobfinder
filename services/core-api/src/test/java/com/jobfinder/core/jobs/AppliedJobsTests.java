package com.jobfinder.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** "I applied": the third action beside save and hide (docs/adr/0027-feed-and-feedback.md), with the same ownership rules. */
class AppliedJobsTests extends JobsTestSupport {

    private int actions(UUID user, String action) {
        return count("select count(*) from user_job_actions where user_id = ? and action = ?", user, action);
    }

    @Test
    void markingAJobAppliedFlagsItInSearchAndOnThePageAndKeepsItSearchable() throws Exception {
        UUID job = insert(spec().title("Applied here"));
        Session me = newSession();

        putAs(me, "/jobs/" + job + "/applied").andExpect(status().isNoContent());

        assertThat(titles(getAs(me, "/jobs?q=applied"))).containsExactly("Applied here");
        getAs(me, "/jobs?q=applied").andExpect(jsonPath("$.items[0].applied").value(true));
        getAs(me, "/jobs/" + job).andExpect(jsonPath("$.applied").value(true)).andExpect(jsonPath("$.hidden").value(false));

        deleteAs(me, "/jobs/" + job + "/applied").andExpect(status().isNoContent());
        getAs(me, "/jobs?q=applied").andExpect(jsonPath("$.items[0].applied").value(false));
        getAs(me, "/jobs/" + job).andExpect(jsonPath("$.applied").value(false));
    }

    @Test
    void markingAndUnmarkingAreIdempotent() throws Exception {
        UUID job = insert("Applied here");
        Session me = newSession();

        putAs(me, "/jobs/" + job + "/applied").andExpect(status().isNoContent());
        putAs(me, "/jobs/" + job + "/applied").andExpect(status().isNoContent());
        assertThat(actions(userIdOf(me), "APPLIED")).isEqualTo(1);
        deleteAs(me, "/jobs/" + job + "/applied").andExpect(status().isNoContent());
        deleteAs(me, "/jobs/" + job + "/applied").andExpect(status().isNoContent());
        assertThat(actions(userIdOf(me), "APPLIED")).isZero();
    }

    @Test
    void applyingToAHiddenJobUnhidesIt() throws Exception {
        UUID job = insert("Changed my mind");
        Session me = newSession();
        putAs(me, "/jobs/" + job + "/hide").andExpect(status().isNoContent());

        putAs(me, "/jobs/" + job + "/applied").andExpect(status().isNoContent());

        assertThat(actions(userIdOf(me), "HIDDEN")).isZero();
        assertThat(actions(userIdOf(me), "APPLIED")).isEqualTo(1);
        assertThat(titles(getAs(me, "/jobs"))).containsExactly("Changed my mind");
    }

    @Test
    void applyingDoesNotUnsaveAndSavingDoesNotUnapply() throws Exception {
        UUID job = insert("Both");
        Session me = newSession();

        putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        putAs(me, "/jobs/" + job + "/applied").andExpect(status().isNoContent());
        getAs(me, "/jobs/" + job).andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.applied").value(true));
        deleteAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        getAs(me, "/jobs/" + job).andExpect(jsonPath("$.saved").value(false)).andExpect(jsonPath("$.applied").value(true));
    }

    @Test
    void oneUsersApplicationIsInvisibleToAnotherAndNoRequestCanNameAnotherUser() throws Exception {
        UUID job = insert("Shared posting");
        Session alice = newSession();
        Session bob = newSession();

        putAs(alice, "/jobs/" + job + "/applied").andExpect(status().isNoContent());
        mvc.perform(put("/jobs/" + job + "/applied?userId=" + userIdOf(alice)).header("Authorization", bearer(bob))
                .contentType("application/json").content("{\"userId\":\"" + userIdOf(alice) + "\"}"))
                .andExpect(status().isNoContent());
        deleteAs(bob, "/jobs/" + job + "/applied").andExpect(status().isNoContent());

        getAs(alice, "/jobs/" + job).andExpect(jsonPath("$.applied").value(true));
        getAs(bob, "/jobs/" + job).andExpect(jsonPath("$.applied").value(false));
        assertThat(actions(userIdOf(alice), "APPLIED")).isEqualTo(1);
        assertThat(actions(userIdOf(bob), "APPLIED")).isZero();
    }

    @Test
    void anonymousCallersAndUnknownJobsAreRefused() throws Exception {
        UUID job = insert("Shared posting");
        for (var request : List.of(put("/jobs/" + job + "/applied"), delete("/jobs/" + job + "/applied"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        Session me = newSession();

        putAs(me, "/jobs/" + UUID.randomUUID() + "/applied").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
        deleteAs(me, "/jobs/" + UUID.randomUUID() + "/applied").andExpect(status().isNotFound());
        assertThat(count("select count(*) from user_job_actions")).isZero();
    }
}
