package com.jobfinder.core.profile;

import com.jobfinder.core.CoversEndpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

class ResumeManagementTests extends ResumeTestSupport {

    private static String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    private List<String> listedIds(Session session) throws Exception {
        String body = mvc.perform(get("/resumes").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
    }

    @CoversEndpoints({"GET /resumes", "POST /resumes"})
    @Test
    void listsOnlyTheCallersResumesNewestFirst() throws Exception {
        Session me = newSession();
        Session other = newSession();
        UUID first = uploadPdf(me);
        UUID second = uploadPdf(me);
        UUID theirs = uploadPdf(other);

        assertThat(listedIds(me)).containsExactly(second.toString(), first.toString());
        assertThat(listedIds(other)).containsExactly(theirs.toString());
    }

    @Test
    void setPrimaryMovesThePrimaryFlagAndKeepsExactlyOne() throws Exception {
        Session session = newSession();
        UUID first = uploadPdf(session);
        UUID second = uploadPdf(session);

        mvc.perform(put("/resumes/" + second + "/primary").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.primary").value(true));

        assertThat(count("select count(*) from resumes where user_id = ? and is_primary",
                userIdOf(session.accessToken()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select is_primary from resumes where id = ?", Boolean.class, first)).isFalse();
        assertThat(jdbc.queryForObject("select is_primary from resumes where id = ?", Boolean.class, second)).isTrue();

        // Idempotent.
        mvc.perform(put("/resumes/" + second + "/primary").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.primary").value(true));
    }

    @Test
    void deletingRemovesTheRowItsVersionsAndTheStoredFile() throws Exception {
        Session session = newSession();
        UUID id = uploadPdf(session);
        String key = fileKey(id);
        assertThat(objectExists(key)).isTrue();

        mvc.perform(delete("/resumes/" + id).header("Authorization", bearer(session)))
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from resumes where id = ?", id)).isZero();
        assertThat(count("select count(*) from resume_versions where resume_id = ?", id)).isZero();
        assertThat(objectExists(key)).isFalse();
        mvc.perform(get("/resumes/" + id + "/download-url").header("Authorization", bearer(session)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingThePrimaryPromotesTheNewestRemainingResume() throws Exception {
        Session session = newSession();
        UUID primary = uploadPdf(session);
        UUID older = uploadPdf(session);
        UUID newest = uploadPdf(session);

        mvc.perform(delete("/resumes/" + primary).header("Authorization", bearer(session)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select is_primary from resumes where id = ?", Boolean.class, newest)).isTrue();
        assertThat(jdbc.queryForObject("select is_primary from resumes where id = ?", Boolean.class, older)).isFalse();
    }

    @Test
    void deletingTheLastResumeLeavesNoneAndTheNextUploadIsPrimaryAgain() throws Exception {
        Session session = newSession();
        mvc.perform(delete("/resumes/" + uploadPdf(session)).header("Authorization", bearer(session)))
                .andExpect(status().isNoContent());

        assertThat(listedIds(session)).isEmpty();
        upload(session, "again.pdf", "application/pdf", pdf()).andExpect(jsonPath("$.primary").value(true));
    }

    @CoversEndpoints({"GET /resumes/{id}/download-url", "PUT /resumes/{id}/primary", "DELETE /resumes/{id}"})
    @Test
    void otherUsersCannotSeeDownloadChangeOrDeleteMyResume() throws Exception {
        Session owner = newSession();
        Session intruder = newSession();
        UUID id = uploadPdf(owner);
        String key = fileKey(id);

        mvc.perform(get("/resumes/" + id + "/download-url").header("Authorization", bearer(intruder)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("resume_not_found"));
        mvc.perform(put("/resumes/" + id + "/primary").header("Authorization", bearer(intruder)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/resumes/" + id).header("Authorization", bearer(intruder)))
                .andExpect(status().isNotFound());

        assertThat(count("select count(*) from resumes where id = ?", id)).isEqualTo(1);
        assertThat(objectExists(key)).isTrue();
    }

    @Test
    void unknownResumeIsNotFound() throws Exception {
        Session session = newSession();
        mvc.perform(delete("/resumes/" + UUID.randomUUID()).header("Authorization", bearer(session)))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/resumes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/resumes/" + id + "/download-url")).andExpect(status().isUnauthorized());
        mvc.perform(put("/resumes/" + id + "/primary")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/resumes/" + id)).andExpect(status().isUnauthorized());
    }
}
