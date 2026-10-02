package com.jobfinder.core.documents;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.github.tomakehurst.wiremock.client.MappingBuilder;

import tools.jackson.databind.JsonNode;

/**
 * Reviewing a cover letter: an edit persists (a reload shows it, the version goes up, a stale version is a 409), every
 * edit re-runs the fact check so text that invents an employer cannot be approved until it is fixed, a placeholder is
 * refused, approval recomputes the check, and an approved letter is immutable in the API and in the database.
 */
class LetterReviewTests extends WritingTestSupport {

    private record Draft(Session session, Candidate candidate, UUID job, String id) {
    }

    private Draft draft() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubTextCheck(candidate);
        // A text that names FakeCorp is flagged by the (stubbed) text fact check; anything else passes.
        aiService.stubFor(blockedWhenMentioning(candidate, INVENTED)
                .willReturn(okJson(blockingTextFlag("NEW_EMPLOYER", "paragraphs[1]", INVENTED))));
        UUID job = newJob();
        stubLetter(candidate.userId(), letterOk(candidate, UUID.randomUUID(), "0.003"));
        String id = idOf(coverLetter(me, job, null).andExpect(status().isCreated()));
        return new Draft(me, candidate, job, id);
    }

    private MappingBuilder blockedWhenMentioning(Candidate c, String word) {
        return post(urlPathEqualTo(TEXT_CHECK_PATH)).atPriority(5)
                .withRequestBody(matchingJsonPath("$.source.contact.full_name",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(c.name())))
                .withRequestBody(matchingJsonPath("$.texts[?(@.text =~ /.*" + word + ".*/)]"));
    }

    @Test
    void anEditPersistsAndIsVisibleOnReload() throws Exception {
        Draft d = draft();

        patchAs(d.session(), d.id(), edit(1, "paragraphs[2]", "I would be glad to talk it through. Thank you."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.content.paragraphs[2]").value("I would be glad to talk it through. Thank you."));

        getDocument(d.session(), d.id()).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.content.paragraphs[2]").value("I would be glad to talk it through. Thank you."))
                .andExpect(jsonPath("$.content.paragraphs[0]").value(org.hamcrest.Matchers.containsString("Harbor")));
        // The other parts of the letter are untouched.
        assertThat(jdbc.queryForObject("select content->>'closing' from generated_documents where id = ?::uuid",
                String.class, d.id())).isEqualTo("Yours sincerely,");
    }

    @Test
    void everyEditCanBeAppliedToTheSalutationClosingAndSignatureToo() throws Exception {
        Draft d = draft();

        patchAs(d.session(), d.id(), "{\"version\":1,\"operations\":["
                + "{\"op\":\"EDIT\",\"path\":\"salutation\",\"after\":\"Dear Ms Okafor,\"},"
                + "{\"op\":\"EDIT\",\"path\":\"closing\",\"after\":\"Kind regards,\"},"
                + "{\"op\":\"EDIT\",\"path\":\"signature\",\"after\":\"J. Ikeji\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.content.salutation").value("Dear Ms Okafor,"))
                .andExpect(jsonPath("$.content.closing").value("Kind regards,"))
                .andExpect(jsonPath("$.content.signature").value("J. Ikeji"));
    }

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() throws Exception {
        Draft d = draft();
        patchAs(d.session(), d.id(), edit(1, "salutation", "Dear team,")).andExpect(status().isOk());

        patchAs(d.session(), d.id(), edit(1, "salutation", "Dear everyone,")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version_conflict")).andExpect(jsonPath("$.currentVersion").value(2));

        getDocument(d.session(), d.id()).andExpect(jsonPath("$.content.salutation").value("Dear team,"));
    }

    @Test
    void everyEditReRunsTheFactCheckOnTheLetterAndSendsTheJobAndNotesAsAllowedContext() throws Exception {
        Draft d = draft();
        int before = textCheckRequests(d.candidate()).size();

        patchAs(d.session(), d.id(), edit(1, "paragraphs[1]", "I led the billing platform work.")).andExpect(status().isOk());

        assertThat(textCheckRequests(d.candidate())).hasSize(before + 1);
        JsonNode sent = mapper.readTree(textCheckRequests(d.candidate()).get(before).getBodyAsString());
        // The whole letter is checked, by the path the flags use, against the resume the letter was written from.
        assertThat(sent.get("texts").get(0).get("path").asString()).isEqualTo("salutation");
        assertThat(sent.get("texts").toString()).contains("paragraphs[0]", "paragraphs[1]", "paragraphs[2]", "closing")
                .contains("I led the billing platform work.");
        assertThat(sent.get("source").get("contact").get("full_name").asString()).isEqualTo(d.candidate().name());
        // What the text may name without the resume showing it: the job's own title and company.
        assertThat(sent.get("allowed_context").asString()).contains("Staff Backend Engineer").contains("Acme Test Co");
        assertThat(sent.get("job_description").asString()).contains("Kafka");
    }

    @Test
    void anEditThatInventsAnEmployerBlocksApprovalUntilItIsFixed() throws Exception {
        Draft d = draft();

        patchAs(d.session(), d.id(), edit(1, "paragraphs[1]", "I ran the platform at " + INVENTED + "."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FACT_CHECK_FAILED"))
                .andExpect(jsonPath("$.factCheck.blocking").value(1))
                .andExpect(jsonPath("$.factCheck.flags[0].code").value("NEW_EMPLOYER"))
                .andExpect(jsonPath("$.factCheck.flags[0].value").value(INVENTED));
        approve(d.session(), d.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("fact_check_failed"));
        assertThat(statusOf(d.id())).isEqualTo("FACT_CHECK_FAILED");

        patchAs(d.session(), d.id(), edit(2, "paragraphs[1]", "I led a team of 4 engineers at Northwind Systems."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.factCheck.blocking").value(0));
        approve(d.session(), d.id()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void aPlaceholderInAnEditIsRefusedAndNothingIsStored() throws Exception {
        Draft d = draft();

        for (String text : new String[] { "Dear [Your Name]", "Call me on [phone number]", "NEEDS_INPUT",
                "Hello {{name}}", "Dear <hiring manager>" }) {
            patchAs(d.session(), d.id(), edit(1, "paragraphs[0]", text)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_content"));
        }

        getDocument(d.session(), d.id()).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void malformedEditsAreRefused() throws Exception {
        Draft d = draft();

        // Not a path of a letter, a paragraph that is not there, a change operation, no path, a blank or a non-string text.
        patchAs(d.session(), d.id(), edit(1, "answers.WHY_COMPANY_ROLE", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_operation"));
        patchAs(d.session(), d.id(), edit(1, "paragraphs[9]", "x")).andExpect(status().isBadRequest());
        patchAs(d.session(), d.id(), edit(1, "paragraphs[-1]", "x")).andExpect(status().isBadRequest());
        patchAs(d.session(), d.id(), reject("c1", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_operation"));
        patchAs(d.session(), d.id(), "{\"version\":1,\"operations\":[{\"op\":\"EDIT\",\"after\":\"x\"}]}")
                .andExpect(status().isBadRequest());
        patchAs(d.session(), d.id(), edit(1, "salutation", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_content"));
        patchAs(d.session(), d.id(), edit(1, "paragraphs[0]", "x".repeat(1601))).andExpect(status().isBadRequest());
        patchAs(d.session(), d.id(), "{\"version\":1,\"operations\":[{\"op\":\"EDIT\",\"path\":\"salutation\","
                + "\"after\":[\"x\"]}]}").andExpect(status().isBadRequest());
        patchAs(d.session(), d.id(), "{\"version\":1,\"operations\":[]}").andExpect(status().isBadRequest());

        getDocument(d.session(), d.id()).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void whenTheFactCheckIsUnavailableTheEditIsRefusedAndNothingChanges() throws Exception {
        Draft d = draft();
        stubTextCheckDown(d.candidate());

        patchAs(d.session(), d.id(), edit(1, "salutation", "Dear team,")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("fact_check_unavailable"));

        getDocument(d.session(), d.id()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.content.salutation").value("Dear Hiring Manager,"));
    }

    @Test
    void approvalRecomputesTheFactCheckAndMakesTheLetterFinal() throws Exception {
        Draft d = draft();
        int before = textCheckRequests(d.candidate()).size();

        approve(d.session(), d.id()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isString()).andExpect(jsonPath("$.version").value(2));

        assertThat(textCheckRequests(d.candidate())).hasSize(before + 1);
        approve(d.session(), d.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_approved"));
    }

    @Test
    void anApprovedLetterCannotBeEditedOrDeletedInTheApiOrTheDatabase() throws Exception {
        Draft d = draft();
        approve(d.session(), d.id()).andExpect(status().isOk());

        patchAs(d.session(), d.id(), edit(2, "salutation", "Dear team,")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_approved"));
        deleteDocument(d.session(), d.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_approved"));
        assertThatThrownBy(() -> jdbc.update("update generated_documents set content = '{}'::jsonb "
                + "where id = ?::uuid", d.id())).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from generated_documents where id = ?::uuid", d.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable");
        assertThat(statusOf(d.id())).isEqualTo("APPROVED");
    }

    @Test
    void aBlockingFlagCannotBeStoredOnAnApprovedLetterRow() throws Exception {
        Draft d = draft();

        assertThatThrownBy(() -> jdbc.update("""
                insert into generated_documents (id, user_id, type, status, job_id, job_title, base_resume_version_id,
                        prompt_version, source_content, content, changes, fact_check, version, created_at, updated_at,
                        approved_at)
                values (?, ?, 'COVER_LETTER', 'APPROVED', ?, 't', ?, 'cover_letter/v1', '{}'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '{"passed":false,"blocking":1,"warnings":0,"flags":[]}'::jsonb, 1, now(), now(),
                        now())
                """, UUID.randomUUID(), d.candidate().userId(), d.job(), d.candidate().versionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anotherUsersLetterCannotBeEditedOrApprovedAndTheDraftIsUntouched() throws Exception {
        Draft d = draft();
        Session other = newSession();
        seed(other);

        patchAs(other, d.id(), edit(1, "salutation", "Hacked,")).andExpect(status().isNotFound());
        approve(other, d.id()).andExpect(status().isNotFound());

        getDocument(d.session(), d.id()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.content.salutation").value("Dear Hiring Manager,"));
    }
}
