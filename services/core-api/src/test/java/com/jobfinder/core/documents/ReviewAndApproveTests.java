package com.jobfinder.core.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/**
 * Reviewing a draft (accept, reject, edit; the fact check re-run on every edit), approving it (refused on a BLOCKING
 * flag, allowed once the offending change is rejected), and what an approved document allows (nothing).
 */
class ReviewAndApproveTests extends DocumentsTestSupport {

    private static final String EDIT_FAKECORP = """
            {"version":%d,"operations":[{"op":"EDIT","changeId":"c2","after":{"company":"FakeCorp",
              "title":"Principal Engineer","start_date":"2021-03","end_date":null,"is_current":true,
              "bullets":["Ran the platform."]}}]}""";

    /** A draft made from the faithful tailoring, for a candidate whose fact check is stubbed. */
    private record Draft(Session session, Candidate candidate, String id) {
    }

    private Draft faithfulDraft() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubFactCheck(candidate);
        stubTailor(candidate.userId(), tailorOk(UUID.randomUUID(), "0.003"));
        return new Draft(me, candidate, idOf(tailor(me, newJob()).andExpect(status().isCreated())));
    }

    private Draft inventedDraft() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubFactCheck(candidate);
        stubTailor(candidate.userId(), tailorInvented(UUID.randomUUID()));
        return new Draft(me, candidate, idOf(tailor(me, newJob()).andExpect(status().isCreated())));
    }

    // --- review ---

    @Test
    void aDraftCanBeReadByItsOwner() throws Exception {
        Draft d = faithfulDraft();

        getAs(d.session(), "/documents/" + d.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(d.id())).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.changes.length()").value(4));
    }

    @Test
    void rejectingAChangePutsTheSourceBackAndReRunsTheFactCheck() throws Exception {
        Draft d = faithfulDraft();
        int before = factCheckRequests(d.candidate());

        patchAs(d.session(), d.id(), reject("c2", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.changes[?(@.id == 'c2')].state").value("REJECTED"))
                .andExpect(jsonPath("$.changes[?(@.id == 'c3')].state").value("ACCEPTED"))
                // The source's own version of that role is back.
                .andExpect(jsonPath("$.content.experience[0].bullets[0]")
                        .value("Reduced API p95 latency by 35% across 12 services."));

        assertThat(factCheckRequests(d.candidate())).isEqualTo(before + 1);
        // The fact check ran on the content that is now stored: source and candidate are both sent.
        var sent = aiService.findAll(com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(FACT_CHECK_PATH))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                        "$.source.contact.full_name",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(d.candidate().name()))));
        assertThat(JsonPath.<String>read(sent.get(sent.size() - 1).getBodyAsString(),
                "$.candidate.experience[0].bullets[0]")).startsWith("Reduced API p95");
    }

    @Test
    void aRejectedChangeCanBeAcceptedAgain() throws Exception {
        Draft d = faithfulDraft();
        patchAs(d.session(), d.id(), reject("c2", 1)).andExpect(status().isOk());

        patchAs(d.session(), d.id(), """
                {"version":2,"operations":[{"op":"SET_STATE","changeId":"c2","state":"ACCEPTED"}]}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.changes[?(@.id == 'c2')].state").value("ACCEPTED"));
    }

    @Test
    void anEditedTextIsStoredAcceptedAndMarkedEdited() throws Exception {
        Draft d = faithfulDraft();

        patchAs(d.session(), d.id(), """
                {"version":1,"operations":[{"op":"EDIT","changeId":"c1","after":"Backend engineer who ships."}]}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.summary").value("Backend engineer who ships."))
                .andExpect(jsonPath("$.changes[?(@.id == 'c1')].edited").value(true))
                .andExpect(jsonPath("$.changes[?(@.id == 'c1')].state").value("ACCEPTED"));
    }

    @Test
    void aUnitWithoutAChangeCanBeEditedByPath() throws Exception {
        Draft d = faithfulDraft();

        patchAs(d.session(), d.id(), """
                {"version":1,"operations":[{"op":"EDIT","path":"headline","after":"Platform engineer"}]}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.headline").value("Platform engineer"))
                .andExpect(jsonPath("$.changes.length()").value(5))
                .andExpect(jsonPath("$.changes[4].path").value("headline"))
                .andExpect(jsonPath("$.changes[4].before").value("Backend engineer"));
    }

    @Test
    void anOldVersionIsRefusedWithTheCurrentOne() throws Exception {
        Draft d = faithfulDraft();
        patchAs(d.session(), d.id(), reject("c2", 1)).andExpect(status().isOk());

        patchAs(d.session(), d.id(), reject("c3", 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version_conflict"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(JsonPath.<java.util.List<String>>read(body(getAs(d.session(), "/documents/" + d.id())),
                "$.changes[?(@.id == 'c3')].state")).containsExactly("ACCEPTED");
    }

    @Test
    void invalidOperationsAreRefusedAndNothingChanges() throws Exception {
        Draft d = faithfulDraft();

        patchAs(d.session(), d.id(), reject("c99", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("change_not_found"));
        patchAs(d.session(), d.id(), "{\"version\":1,\"operations\":[{\"op\":\"SET_STATE\",\"changeId\":\"c1\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_operation"));
        patchAs(d.session(), d.id(), "{\"version\":1,\"operations\":[{\"op\":\"EDIT\",\"changeId\":\"c4\","
                + "\"after\":\"not a list\"}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_content"));
        patchAs(d.session(), d.id(), "{\"version\":1,\"operations\":[{\"op\":\"EDIT\",\"path\":\"experience[9]\","
                + "\"after\":{\"company\":\"A\",\"title\":\"B\"}}]}").andExpect(status().isBadRequest());
        patchAs(d.session(), d.id(), "{\"version\":1,\"operations\":[]}").andExpect(status().isBadRequest());

        assertThat(JsonPath.<Integer>read(body(getAs(d.session(), "/documents/" + d.id())), "$.version"))
                .isEqualTo(1);
    }

    @Test
    void anEditThatInventsAnEmployerFailsTheFactCheckAndNamesTheChange() throws Exception {
        Draft d = faithfulDraft();

        patchAs(d.session(), d.id(), EDIT_FAKECORP.formatted(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FACT_CHECK_FAILED"))
                .andExpect(jsonPath("$.factCheck.blocking").value(1))
                .andExpect(jsonPath("$.factCheck.flags[0].code").value("NEW_EMPLOYER"))
                .andExpect(jsonPath("$.factCheck.flags[0].changeId").value("c2"));
    }

    @Test
    void ifTheFactCheckIsDownAnEditIsRefusedAndNothingIsStored() throws Exception {
        Draft d = faithfulDraft();
        stubFactCheckDown(d.candidate());

        patchAs(d.session(), d.id(), reject("c2", 1)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("fact_check_unavailable"));

        getAs(d.session(), "/documents/" + d.id()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.changes[?(@.id == 'c2')].state").value("ACCEPTED"));
    }

    // --- approve ---

    @Test
    void aCleanDraftCanBeApprovedAndIsFinal() throws Exception {
        Draft d = faithfulDraft();

        approve(d.session(), d.id()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isString())
                .andExpect(jsonPath("$.content.experience[0].company").value("Northwind Systems"));

        assertThat(statusOf(d.id())).isEqualTo("APPROVED");
    }

    @Test
    void aDraftWithABlockingFlagCannotBeApprovedUntilTheOffendingChangeIsRejected() throws Exception {
        Draft d = inventedDraft();

        approve(d.session(), d.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("fact_check_failed")).andExpect(jsonPath("$.blocking").value(1));
        assertThat(statusOf(d.id())).isEqualTo("FACT_CHECK_FAILED");

        // Rejecting the change that invented the employer clears the flag: the check runs again on what is left.
        patchAs(d.session(), d.id(), reject("c5", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.factCheck.blocking").value(0))
                .andExpect(jsonPath("$.content.experience.length()").value(2));

        String approved = body(approve(d.session(), d.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")));
        // The rejected change stays in the list (as REJECTED) but is not in the final document.
        assertThat(JsonPath.<Object>read(approved, "$.content").toString()).doesNotContain(INVENTED);
        assertThat(JsonPath.<java.util.List<String>>read(approved, "$.changes[?(@.id == 'c5')].state"))
                .containsExactly("REJECTED");
    }

    @Test
    void anEditThatRemovesTheInventionAlsoClearsTheFlag() throws Exception {
        Draft d = inventedDraft();

        patchAs(d.session(), d.id(), """
                {"version":1,"operations":[{"op":"EDIT","changeId":"c5","after":{"company":"Northwind Systems",
                  "title":"Senior Backend Engineer","start_date":"2021-03","end_date":null,"is_current":true,
                  "bullets":["Built services."]}}]}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        approve(d.session(), d.id()).andExpect(status().isOk());
    }

    @Test
    void approvalChecksTheFinalContentAgainNotJustTheStoredFlags() throws Exception {
        Draft d = faithfulDraft();
        // Stored as clean, but the check now finds a blocking flag in that same content: it must not be approved.
        stubFactCheckAlways(d.candidate(), blockingEmployer("experience[0].company"));

        approve(d.session(), d.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("fact_check_failed"));

        assertThat(statusOf(d.id())).isEqualTo("FACT_CHECK_FAILED");
    }

    @Test
    void approvingTwiceIsAConflict() throws Exception {
        Draft d = faithfulDraft();
        approve(d.session(), d.id()).andExpect(status().isOk());

        approve(d.session(), d.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_approved"));
    }

    // --- immutability through the API ---

    @Test
    void anApprovedDocumentCannotBeEditedOrDeleted() throws Exception {
        Draft d = faithfulDraft();
        String approved = body(approve(d.session(), d.id()).andExpect(status().isOk()));

        patchAs(d.session(), d.id(), reject("c2", 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_approved"));
        patchAs(d.session(), d.id(), EDIT_FAKECORP.formatted(1)).andExpect(status().isConflict());
        deleteDocument(d.session(), d.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_approved"));

        String after = body(getAs(d.session(), "/documents/" + d.id()).andExpect(status().isOk()));
        assertThat(JsonPath.<Object>read(after, "$.content")).isEqualTo(JsonPath.<Object>read(approved, "$.content"));
        assertThat(JsonPath.<Integer>read(after, "$.version")).isEqualTo(JsonPath.<Integer>read(approved, "$.version"));
        assertThat(JsonPath.<String>read(after, "$.approvedAt")).isEqualTo(JsonPath.<String>read(approved, "$.approvedAt"));
    }

    @Test
    void aTailoringRequestAfterApprovalMakesANewDraftAndLeavesTheApprovedOneAlone() throws Exception {
        Draft d = faithfulDraft();
        String approvedId = idOfApproved(d);
        UUID job = UUID.fromString(JsonPath.read(body(getAs(d.session(), "/documents/" + approvedId)), "$.job.id"));
        stubTailor(d.candidate().userId(), tailorOk(UUID.randomUUID(), "0.003"));

        String again = idOf(tailor(d.session(), job).andExpect(status().isCreated()));

        assertThat(again).isNotEqualTo(approvedId);
        assertThat(statusOf(approvedId)).isEqualTo("APPROVED");
    }

    private String idOfApproved(Draft d) throws Exception {
        approve(d.session(), d.id()).andExpect(status().isOk());
        return d.id();
    }

    // --- delete ---

    @Test
    void aDraftCanBeDeleted() throws Exception {
        Draft d = faithfulDraft();

        deleteDocument(d.session(), d.id()).andExpect(status().isNoContent());

        getAs(d.session(), "/documents/" + d.id()).andExpect(status().isNotFound());
        assertThat(documents(d.candidate().userId())).isZero();
    }

    // --- authz ---

    @Test
    void anotherUserCannotReadChangeApproveOrDeleteMyDraft() throws Exception {
        Draft mine = faithfulDraft();
        Session other = newSession();
        seed(other);

        getAs(other, "/documents/" + mine.id()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("document_not_found"));
        patchAs(other, mine.id(), reject("c2", 1)).andExpect(status().isNotFound());
        approve(other, mine.id()).andExpect(status().isNotFound());
        deleteDocument(other, mine.id()).andExpect(status().isNotFound());
        getAs(other, "/documents").andExpect(jsonPath("$.items.length()").value(0));

        // Nothing happened to it.
        getAs(mine.session(), "/documents/" + mine.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void anotherUserCannotReadMyApprovedDocumentEither() throws Exception {
        Draft mine = faithfulDraft();
        approve(mine.session(), mine.id()).andExpect(status().isOk());
        Session other = newSession();

        getAs(other, "/documents/" + mine.id()).andExpect(status().isNotFound());
        deleteDocument(other, mine.id()).andExpect(status().isNotFound());
    }

    @Test
    void theDocumentEndpointsNeedASignedInUser() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/documents"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/documents/" + id))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/documents/" + id))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/documents/" + id + "/approve"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/documents/" + id))
                .andExpect(status().isUnauthorized());
    }
}
