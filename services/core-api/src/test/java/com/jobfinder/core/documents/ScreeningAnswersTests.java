package com.jobfinder.core.documents;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * POST /jobs/{id}/screening-answers: the fixed catalogue of questions, the profile facts that are sent (and only the
 * ones that were really stated), the questions the profile cannot answer (NEEDS_INPUT, which keeps the set from being
 * approved until the user writes them), edits, the fact check of the model's own answers only, and responses that
 * must never be stored. The factual answers themselves are made by ai-service's code from those facts; its tests pin
 * that, and the stub here is the file those tests write.
 */
class ScreeningAnswersTests extends WritingTestSupport {

    private record Setup(Session session, Candidate candidate, UUID job) {
    }

    private Setup setup() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubTextCheck(candidate);
        aiService.stubFor(post(urlPathEqualTo(TEXT_CHECK_PATH)).atPriority(5)
                .withRequestBody(matchingJsonPath("$.source.contact.full_name", equalTo(candidate.name())))
                .withRequestBody(matchingJsonPath("$.texts[?(@.text =~ /.*" + INVENTED + ".*/)]"))
                .willReturn(okJson(blockingTextFlag("NEW_EMPLOYER", "answers.STRENGTHS", INVENTED))));
        return new Setup(me, candidate, newJob());
    }

    private String generate(Setup s) throws Exception {
        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), UUID.randomUUID(), "0.002"));
        return idOf(screeningAnswers(s.session(), s.job(), null).andExpect(status().isCreated()));
    }

    @Test
    void itRequiresASignedInUser() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/jobs/" + UUID.randomUUID() + "/screening-answers")).andExpect(status().isUnauthorized());
    }

    @Test
    void noResumeAndAnUnknownJobAreRefused() throws Exception {
        Session me = newSession();
        screeningAnswers(me, newJob(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resume_required"));
        seed(me);
        screeningAnswers(me, UUID.randomUUID(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
    }

    @Test
    void theAnswersAreTheFixedCatalogueWithTheFactualOnesFromTheProfileAndTheRestToWrite() throws Exception {
        Setup s = setup();

        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), UUID.randomUUID(), "0.002"));
        screeningAnswers(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SCREENING_ANSWERS"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.promptVersion").value("screening_answers/v1"))
                .andExpect(jsonPath("$.content.answers.length()").value(10))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'WHY_COMPANY_ROLE')].status").value("GENERATED"))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'SALARY_EXPECTATION')].status").value("FROM_PROFILE"))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'NOTICE_PERIOD')].status").value("NEEDS_INPUT"))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'NOTICE_PERIOD')].answer").value(""))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'NOTICE_PERIOD')].hint").isNotEmpty())
                .andExpect(jsonPath("$.content.answers[?(@.id == 'WORK_AUTHORIZATION')].status").value("NEEDS_INPUT"))
                .andExpect(jsonPath("$.changes.length()").value(0))
                .andExpect(jsonPath("$.factCheck.flags[0].code").value("NEW_SKILL"));
    }

    @Test
    void theProfileFactsAreSentButOnlyWhatWasStated() throws Exception {
        Setup s = setup();
        saveProfile(s.candidate().userId(), 8, "Lagos, Nigeria|Abuja", 6_500_000, false);
        generate(s);
        Session other = newSession();
        Candidate sponsored = seed(other);
        stubTextCheck(sponsored);
        saveProfile(sponsored.userId(), 3, "Berlin", 70_000, true);
        stubAnswers(sponsored.userId(), answersOk(sponsored, UUID.randomUUID(), "0.002"));
        screeningAnswers(other, s.job(), null).andExpect(status().isCreated());
        Session bare = newSession();
        Candidate nothing = seed(bare);
        stubTextCheck(nothing);
        stubAnswers(nothing.userId(), answersOk(nothing, UUID.randomUUID(), "0.002"));
        screeningAnswers(bare, s.job(), null).andExpect(status().isCreated());

        JsonNode first = mapper.readTree(answersRequests(s.candidate().userId()).get(0).getBodyAsString());
        assertThat(first.get("profile").get("years_experience").asInt()).isEqualTo(8);
        JsonNode prefs = first.get("profile").get("preferences");
        assertThat(prefs.get("min_salary").asInt()).isEqualTo(6_500_000);
        assertThat(prefs.get("currency").asString()).isEqualTo("NGN");
        assertThat(prefs.get("locations").toString()).contains("Lagos, Nigeria").contains("Abuja");
        assertThat(prefs.get("work_modes").toString()).contains("REMOTE");
        // "No sponsorship needed" is the column's default, so it is not a statement and is not sent.
        assertThat(prefs.has("needs_sponsorship")).isFalse();
        assertThat(first.get("job").get("title").asString()).isEqualTo("Staff Backend Engineer");
        assertThat(first.get("job").get("skills").isArray()).isTrue();

        JsonNode yes = mapper.readTree(answersRequests(sponsored.userId()).get(0).getBodyAsString());
        assertThat(yes.get("profile").get("preferences").get("needs_sponsorship").asBoolean()).isTrue();

        // Without a profile or preferences nothing is invented: no years, no salary.
        JsonNode none = mapper.readTree(answersRequests(nothing.userId()).get(0).getBodyAsString());
        assertThat(none.get("profile").has("years_experience")).isFalse();
        assertThat(none.get("profile").get("preferences").has("min_salary")).isFalse();
    }

    @Test
    void theSetCannotBeApprovedWhileAQuestionHasNoAnswer() throws Exception {
        Setup s = setup();
        String id = generate(s);

        approve(s.session(), id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("answers_incomplete"))
                .andExpect(jsonPath("$.needsInput[0]").value("NOTICE_PERIOD"))
                .andExpect(jsonPath("$.needsInput[1]").value("WORK_AUTHORIZATION"));
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void writingTheMissingAnswersMakesTheSetApprovable() throws Exception {
        Setup s = setup();
        String id = generate(s);

        patchAs(s.session(), id, edit(1, "answers.NOTICE_PERIOD", "Two months.")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'NOTICE_PERIOD')].answer").value("Two months."))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'NOTICE_PERIOD')].status").value("USER_PROVIDED"))
                .andExpect(jsonPath("$.content.answers[?(@.id == 'NOTICE_PERIOD')].hint").doesNotExist());
        approve(s.session(), id).andExpect(status().isConflict()).andExpect(jsonPath("$.needsInput.length()").value(1))
                .andExpect(jsonPath("$.needsInput[0]").value("WORK_AUTHORIZATION"));
        patchAs(s.session(), id, edit(2, "answers.WORK_AUTHORIZATION", "I am authorized and need no sponsorship."))
                .andExpect(status().isOk());

        approve(s.session(), id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        getDocument(s.session(), id).andExpect(jsonPath("$.content.answers[?(@.id == 'NOTICE_PERIOD')].answer")
                .value("Two months."));
        patchAs(s.session(), id, edit(4, "answers.NOTICE_PERIOD", "One month.")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_approved"));
    }

    @Test
    void aFactualAnswerTheUserEditsBecomesTheirsAndAModelAnswerStaysTheModels() throws Exception {
        Setup s = setup();
        String id = generate(s);

        patchAs(s.session(), id, edit(1, "answers.SALARY_EXPECTATION", "Negotiable, from NGN 7,000,000."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.answers[?(@.id == 'SALARY_EXPECTATION')].status").value("USER_PROVIDED"));
        patchAs(s.session(), id, edit(2, "answers.STRENGTHS", "My strengths are Java and Kafka at Northwind Systems."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.answers[?(@.id == 'STRENGTHS')].status").value("GENERATED"));
    }

    @Test
    void onlyTheModelsOwnAnswersAreFactChecked() throws Exception {
        Setup s = setup();
        String id = generate(s);
        int before = textCheckRequests(s.candidate()).size();

        patchAs(s.session(), id, edit(1, "answers.NOTICE_PERIOD", "Two months.")).andExpect(status().isOk());

        JsonNode sent = mapper.readTree(textCheckRequests(s.candidate()).get(before).getBodyAsString());
        List<String> paths = new ArrayList<>();
        sent.get("texts").forEach(t -> paths.add(t.get("path").asString()));
        assertThat(paths).containsExactly("answers.WHY_COMPANY_ROLE", "answers.STRENGTHS", "answers.GROWTH_AREA",
                "answers.BIGGEST_ACHIEVEMENT");
    }

    @Test
    void aModelAnswerThatInventsAnEmployerBlocksApprovalEvenWhenEverythingIsAnswered() throws Exception {
        Setup s = setup();
        String id = generate(s);
        patchAs(s.session(), id, edit(1, "answers.NOTICE_PERIOD", "Two months.")).andExpect(status().isOk());
        patchAs(s.session(), id, edit(2, "answers.WORK_AUTHORIZATION", "Authorized, no sponsorship needed."))
                .andExpect(status().isOk());

        patchAs(s.session(), id, edit(3, "answers.STRENGTHS", "I ran the platform at " + INVENTED + "."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FACT_CHECK_FAILED"))
                .andExpect(jsonPath("$.factCheck.flags[0].path").value("answers.STRENGTHS"));

        approve(s.session(), id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("fact_check_failed"));
    }

    @Test
    void malformedAnswerEditsAreRefused() throws Exception {
        Setup s = setup();
        String id = generate(s);

        patchAs(s.session(), id, edit(1, "paragraphs[0]", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_operation"));
        patchAs(s.session(), id, edit(1, "answers.NO_SUCH_QUESTION", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_operation"));
        patchAs(s.session(), id, edit(1, "answers.NOTICE_PERIOD", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_content"));
        patchAs(s.session(), id, edit(1, "answers.NOTICE_PERIOD", "[date]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_content"));
        patchAs(s.session(), id, edit(1, "answers.NOTICE_PERIOD", "NEEDS_INPUT")).andExpect(status().isBadRequest());
        patchAs(s.session(), id, edit(1, "answers.NOTICE_PERIOD", "x".repeat(1201))).andExpect(status().isBadRequest());

        getDocument(s.session(), id).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void responsesThatMustNeverBeStoredAreRefused() throws Exception {
        Setup s = setup();
        UUID placeholderCall = UUID.randomUUID();

        // A placeholder in a model answer.
        ObjectNode placeholder = answersOk(s.candidate(), placeholderCall, "0.002");
        answer(placeholder, "STRENGTHS").put("answer", "My strengths are [Your Name]'s.");
        stubAnswers(s.candidate().userId(), placeholder);
        screeningAnswers(s.session(), s.job(), null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("writing_unavailable"));
        assertThat(callStatus(placeholderCall)).isEqualTo("FAILED");
        assertThat(featureOf(placeholderCall)).isEqualTo("screening_answers");

        // An unanswered question that carries text, and an answered one that carries none.
        ObjectNode textOnNeedsInput = answersOk(s.candidate(), UUID.randomUUID(), "0.002");
        answer(textOnNeedsInput, "NOTICE_PERIOD").put("answer", "Immediately");
        stubAnswers(s.candidate().userId(), textOnNeedsInput);
        screeningAnswers(s.session(), s.job(), "{\"tone\":\"WARM\"}").andExpect(status().isServiceUnavailable());
        ObjectNode emptyAnswer = answersOk(s.candidate(), UUID.randomUUID(), "0.002");
        answer(emptyAnswer, "STRENGTHS").put("answer", "");
        stubAnswers(s.candidate().userId(), emptyAnswer);
        screeningAnswers(s.session(), s.job(), "{\"tone\":\"CONCISE\"}").andExpect(status().isServiceUnavailable());

        // A catalogue with a question missing or repeated.
        ObjectNode missing = answersOk(s.candidate(), UUID.randomUUID(), "0.002");
        ((ArrayNode) missing.get("answers")).remove(9);
        stubAnswers(s.candidate().userId(), missing);
        screeningAnswers(s.session(), s.job(), "{\"length\":\"LONG\"}").andExpect(status().isServiceUnavailable());
        ObjectNode repeated = answersOk(s.candidate(), UUID.randomUUID(), "0.002");
        ((ObjectNode) repeated.get("answers").get(9)).put("id", "STRENGTHS");
        stubAnswers(s.candidate().userId(), repeated);
        screeningAnswers(s.session(), s.job(), "{\"length\":\"SHORT\"}").andExpect(status().isServiceUnavailable());

        assertThat(documents(s.candidate().userId())).isZero();
    }

    private static ObjectNode answer(ObjectNode body, String id) {
        for (JsonNode a : body.get("answers")) {
            if (id.equals(a.get("id").asString())) {
                return (ObjectNode) a;
            }
        }
        throw new IllegalArgumentException(id);
    }

    @Test
    void theSameOptionsReturnTheDraftAndOtherOptionsKeepTheOldSetAsHistory() throws Exception {
        Setup s = setup();
        String first = generate(s);

        String again = idOf(screeningAnswers(s.session(), s.job(), null).andExpect(status().isOk()));
        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), UUID.randomUUID(), "0.002"));
        String warm = idOf(screeningAnswers(s.session(), s.job(), "{\"tone\":\"WARM\",\"length\":\"SHORT\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.options.tone").value("WARM")));

        assertThat(again).isEqualTo(first);
        assertThat(warm).isNotEqualTo(first);
        assertThat(statusOf(first)).isEqualTo("SUPERSEDED");
        assertThat(answersRequests(s.candidate().userId())).hasSize(2);
    }

    @Test
    void usageIsRecordedUnderTheScreeningAnswersFeature() throws Exception {
        Setup s = setup();
        UUID callId = UUID.randomUUID();
        stubAnswers(s.candidate().userId(), answersOk(s.candidate(), callId, "0.002"));

        screeningAnswers(s.session(), s.job(), null).andExpect(status().isCreated());

        assertThat(featureOf(callId)).isEqualTo("screening_answers");
        assertThat(callStatus(callId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select cost_micro_usd from ai_calls where request_key = ?", Long.class,
                "ai-service:" + callId)).isEqualTo(2_000L);
    }

    @Test
    void anApprovedSetWithAQuestionStillUnansweredCannotExistInTheDatabase() throws Exception {
        Setup s = setup();
        String id = generate(s);

        assertThatThrownBy(() -> jdbc.update("update generated_documents set status = 'APPROVED', approved_at = now() "
                + "where id = ?::uuid", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void anotherUsersAnswersAreNotFound() throws Exception {
        Setup s = setup();
        String id = generate(s);
        Session other = newSession();
        seed(other);

        getDocument(other, id).andExpect(status().isNotFound());
        patchAs(other, id, edit(1, "answers.NOTICE_PERIOD", "x")).andExpect(status().isNotFound());
        approve(other, id).andExpect(status().isNotFound());
        deleteDocument(other, id).andExpect(status().isNotFound());
    }
}
