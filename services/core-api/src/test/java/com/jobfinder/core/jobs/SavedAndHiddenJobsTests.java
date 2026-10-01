package com.jobfinder.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Save and hide are the caller's own state: scoped by the token, invisible to anyone else, gone with the account. */
class SavedAndHiddenJobsTests extends JobsTestSupport {

    private int actions(UUID user, String action) {
        return count("select count(*) from user_job_actions where user_id = ? and action = ?", user, action);
    }

    @Test
    void savingAJobListsItAndFlagsItInSearchAndOnThePage() throws Exception {
        UUID job = insert(spec().title("Keeper"));
        insert("Other");
        Session me = newSession();

        putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());

        assertThat(titles(getAs(me, "/saved-jobs"))).containsExactly("Keeper");
        getAs(me, "/jobs?q=keeper").andExpect(jsonPath("$.items[0].saved").value(true));
        getAs(me, "/jobs/" + job).andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.hidden").value(false));
        getAs(me, "/saved-jobs").andExpect(jsonPath("$.items[0].savedAt").exists());

        deleteAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        assertThat(titles(getAs(me, "/saved-jobs"))).isEmpty();
        getAs(me, "/jobs/" + job).andExpect(jsonPath("$.saved").value(false));
    }

    @Test
    void savingAndRemovingAreIdempotent() throws Exception {
        UUID job = insert("Keeper");
        Session me = newSession();

        putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        assertThat(actions(userIdOf(me), "SAVED")).isEqualTo(1);
        deleteAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        deleteAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        deleteAs(me, "/jobs/" + job + "/hide").andExpect(status().isNoContent());
        assertThat(actions(userIdOf(me), "SAVED")).isZero();
    }

    @Test
    void savedJobsComeNewestSavedFirstAndPage() throws Exception {
        List<UUID> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            jobs.add(insert("Job " + i));
        }
        Session me = newSession();
        for (UUID job : jobs) {
            putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        }

        List<String> seen = allIds(me, "/saved-jobs?limit=3", 5);

        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyElementsOf(jdbc.queryForList(
                "select job_id::text from user_job_actions where user_id = ? and action = 'SAVED' "
                        + "order by created_at desc, job_id desc", String.class, userIdOf(me)));
    }

    @Test
    void savedJobsThatSavedAtTheSameInstantPageWithoutLoss() throws Exception {
        Session me = newSession();
        for (int i = 0; i < 6; i++) {
            putAs(me, "/jobs/" + insert("Job " + i) + "/save").andExpect(status().isNoContent());
        }
        jdbc.update("update user_job_actions set created_at = '2026-09-01T10:00:00Z' where user_id = ?", userIdOf(me));

        assertThat(allIds(me, "/saved-jobs?limit=2", 5)).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    void aSavedJobStaysListedAfterItExpires() throws Exception {
        UUID job = insert("Short lived");
        Session me = newSession();
        putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        jdbc.update("update jobs set status = 'EXPIRED' where id = ?", job);

        getAs(me, "/saved-jobs").andExpect(jsonPath("$.items[0].title").value("Short lived"))
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
        assertThat(titles(getAs(me, "/jobs?q=short"))).isEmpty();
        getAs(me, "/jobs/" + job).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void aHiddenJobLeavesMySearchesAndListingsButNotAnyoneElses() throws Exception {
        UUID job = insert(spec().title("Unwanted").postedAt(Instant.now().minus(Duration.ofDays(1))));
        insert(spec().title("Wanted").postedAt(Instant.now().minus(Duration.ofDays(2))));
        Session me = newSession();
        Session other = newSession();

        putAs(me, "/jobs/" + job + "/hide").andExpect(status().isNoContent());

        assertThat(titles(getAs(me, "/jobs"))).containsExactly("Wanted");
        assertThat(titles(getAs(me, "/jobs?q=unwanted"))).isEmpty();
        assertThat(titles(getAs(other, "/jobs"))).containsExactly("Unwanted", "Wanted");
        // Hiding is not deleting: the job page still opens (that is where it is un-hidden), and says so.
        getAs(me, "/jobs/" + job).andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(true));
        getAs(other, "/jobs/" + job).andExpect(jsonPath("$.hidden").value(false));

        deleteAs(me, "/jobs/" + job + "/hide").andExpect(status().isNoContent());
        assertThat(titles(getAs(me, "/jobs"))).containsExactly("Unwanted", "Wanted");
    }

    @Test
    void hidingAJobUnsavesItAndSavingAHiddenJobUnhidesIt() throws Exception {
        UUID job = insert("Changing my mind");
        Session me = newSession();

        putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        putAs(me, "/jobs/" + job + "/hide").andExpect(status().isNoContent());
        assertThat(actions(userIdOf(me), "SAVED")).isZero();
        assertThat(actions(userIdOf(me), "HIDDEN")).isEqualTo(1);

        putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        assertThat(actions(userIdOf(me), "SAVED")).isEqualTo(1);
        assertThat(actions(userIdOf(me), "HIDDEN")).isZero();
        assertThat(titles(getAs(me, "/jobs"))).containsExactly("Changing my mind");
    }

    // --- ownership ---

    @Test
    void oneUsersStateIsNeverVisibleToOrChangeableByAnother() throws Exception {
        UUID job = insert("Shared posting");
        Session alice = newSession();
        Session bob = newSession();
        putAs(alice, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        putAs(bob, "/jobs/" + job + "/hide").andExpect(status().isNoContent());

        // Neither sees the other's state ...
        assertThat(titles(getAs(bob, "/saved-jobs"))).isEmpty();
        assertThat(titles(getAs(alice, "/saved-jobs"))).containsExactly("Shared posting");
        assertThat(titles(getAs(alice, "/jobs"))).containsExactly("Shared posting");
        assertThat(titles(getAs(bob, "/jobs"))).isEmpty();
        getAs(bob, "/jobs/" + job).andExpect(jsonPath("$.saved").value(false)).andExpect(jsonPath("$.hidden").value(true));
        getAs(alice, "/jobs/" + job).andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.hidden").value(false));

        // ... and removing one's own state leaves the other's alone.
        deleteAs(bob, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        deleteAs(alice, "/jobs/" + job + "/hide").andExpect(status().isNoContent());
        assertThat(actions(userIdOf(alice), "SAVED")).isEqualTo(1);
        assertThat(actions(userIdOf(bob), "HIDDEN")).isEqualTo(1);
    }

    @Test
    void thereIsNoWayToNameAnotherUserInTheRequest() throws Exception {
        UUID job = insert("Shared posting");
        Session alice = newSession();
        Session bob = newSession();
        putAs(alice, "/jobs/" + job + "/save").andExpect(status().isNoContent());

        // A user id in the query string or the body is ignored: the owner is always the token's subject.
        getAs(bob, "/saved-jobs?userId=" + userIdOf(alice)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(put("/jobs/" + job + "/hide?userId=" + userIdOf(alice)).header("Authorization", bearer(bob))
                .contentType("application/json").content("{\"userId\":\"" + userIdOf(alice) + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(actions(userIdOf(alice), "HIDDEN")).isZero();
        assertThat(actions(userIdOf(bob), "HIDDEN")).isEqualTo(1);
    }

    @Test
    void anonymousCallersCannotChangeOrReadState() throws Exception {
        UUID job = insert("Shared posting");

        for (var request : List.of(put("/jobs/" + job + "/save"), delete("/jobs/" + job + "/save"),
                put("/jobs/" + job + "/hide"), delete("/jobs/" + job + "/hide"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        assertThat(count("select count(*) from user_job_actions")).isZero();
    }

    @Test
    void anUnknownJobIs404ForEveryAction() throws Exception {
        Session me = newSession();
        UUID missing = UUID.randomUUID();

        putAs(me, "/jobs/" + missing + "/save").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
        putAs(me, "/jobs/" + missing + "/hide").andExpect(status().isNotFound());
        deleteAs(me, "/jobs/" + missing + "/save").andExpect(status().isNotFound());
        deleteAs(me, "/jobs/" + missing + "/hide").andExpect(status().isNotFound());
        getAs(me, "/jobs/" + missing).andExpect(status().isNotFound());
        getAs(me, "/jobs/not-a-uuid").andExpect(status().isBadRequest());
    }

    // --- lifecycle ---

    @Test
    void deletingAnAccountErasesItsSavedAndHiddenJobsAndOnlyThose() throws Exception {
        UUID job = insert("Posting");
        Session leaving = newSession();
        Session staying = newSession();
        putAs(leaving, "/jobs/" + job + "/save").andExpect(status().isNoContent());
        putAs(staying, "/jobs/" + job + "/hide").andExpect(status().isNoContent());

        deleteAs(leaving, "/me").andExpect(status().isNoContent());

        assertThat(count("select count(*) from user_job_actions where user_id = ?", userIdOf(staying))).isEqualTo(1);
        assertThat(count("select count(*) from user_job_actions")).isEqualTo(1);
    }

    @Test
    void deletingAJobRemovesWhoeverSavedIt() throws Exception {
        UUID job = insert("Posting");
        Session me = newSession();
        putAs(me, "/jobs/" + job + "/save").andExpect(status().isNoContent());

        jdbc.update("delete from jobs where id = ?", job);

        assertThat(count("select count(*) from user_job_actions where job_id = ?", job)).isZero();
        assertThat(titles(getAs(me, "/saved-jobs"))).isEmpty();
    }
}
