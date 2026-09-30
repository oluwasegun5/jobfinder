package com.jobfinder.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProfileDeletionTests extends ResumeTestSupport {

    private void insertProfileAndPreferences(UUID userId) {
        jdbc.update("insert into profiles (id, user_id, full_name, created_at, updated_at) "
                + "values (?, ?, 'Test Person', now(), now())", UUID.randomUUID(), userId);
        jdbc.update("insert into preferences (id, user_id, target_titles, created_at, updated_at) "
                + "values (?, ?, array['Engineer'], now(), now())", UUID.randomUUID(), userId);
    }

    @Test
    void deletingTheAccountErasesProfileDataAndEveryStoredFile() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        UUID first = uploadPdf(session);
        UUID second = uploadPdf(session);
        insertProfileAndPreferences(userId);
        String firstKey = fileKey(first);
        String secondKey = fileKey(second);
        // A file that lost its row (say, an earlier failed delete) must go too.
        String orphan = "resumes/" + userId + "/" + UUID.randomUUID() + ".pdf";
        putObject(orphan);
        assertThat(objectsUnder("resumes/" + userId + "/")).isEqualTo(3);

        mvc.perform(delete("/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from resumes where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from resume_versions where resume_id in (?, ?)", first, second)).isZero();
        assertThat(count("select count(*) from profiles where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from preferences where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from users where id = ?", userId)).isZero();
        assertThat(objectExists(firstKey)).isFalse();
        assertThat(objectExists(secondKey)).isFalse();
        assertThat(objectExists(orphan)).isFalse();
        assertThat(objectsUnder("resumes/" + userId + "/")).isZero();
    }

    @Test
    void deletingOneAccountLeavesOtherUsersDataAndFilesAlone() throws Exception {
        Session bystander = newSession();
        UUID bystanderId = userIdOf(bystander.accessToken());
        UUID kept = uploadPdf(bystander);
        insertProfileAndPreferences(bystanderId);
        Session leaving = newSession();
        uploadPdf(leaving);

        mvc.perform(delete("/me").header("Authorization", "Bearer " + leaving.accessToken()))
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from resumes where id = ?", kept)).isEqualTo(1);
        assertThat(count("select count(*) from profiles where user_id = ?", bystanderId)).isEqualTo(1);
        assertThat(count("select count(*) from preferences where user_id = ?", bystanderId)).isEqualTo(1);
        assertThat(objectExists(fileKey(kept))).isTrue();
    }

    @Test
    void anAccountWithNoFilesDeletesCleanly() throws Exception {
        Session session = newSession();
        mvc.perform(delete("/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());
    }
}
