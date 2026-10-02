package com.jobfinder.core.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * POST /jobs/{id}/cover-letter: who may call it, what is sent to ai-service, the draft that comes back (faithful and
 * with an invented employer), the options, regeneration that keeps history and never touches an approved letter,
 * idempotency and the double click, the daily cap, ai-service failures, a response that must never be stored (a
 * placeholder), and usage recording. ai-service is stubbed with the files its own tests pin.
 */
class CoverLetterTests extends WritingTestSupport {

    @Autowired
    private AiUsageLedger ledger;

    private record Setup(Session session, Candidate candidate, UUID job) {
    }

    private Setup setup() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubTextCheck(candidate);
        return new Setup(me, candidate, newJob());
    }

    @Test
    void itRequiresASignedInUser() throws Exception {
        mvc.perform(post("/jobs/" + UUID.randomUUID() + "/cover-letter")).andExpect(status().isUnauthorized());
    }

    @Test
    void aUserWithoutAParsedResumeGetsAConflict() throws Exception {
        Session me = newSession();

        coverLetter(me, newJob(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resume_required"));
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        Session me = newSession();
        seed(me);

        coverLetter(me, UUID.randomUUID(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("job_not_found"));
    }

    @Test
    void aFaithfulLetterBecomesADraftWithItsSenderAndFlags() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));

        coverLetter(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("COVER_LETTER"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.job.id").value(s.job().toString()))
                .andExpect(jsonPath("$.job.company").value("Acme Test Co"))
                .andExpect(jsonPath("$.baseResumeVersionId").value(s.candidate().versionId().toString()))
                .andExpect(jsonPath("$.promptVersion").value("cover_letter/v1"))
                .andExpect(jsonPath("$.model").value("fake-strong"))
                .andExpect(jsonPath("$.version").value(1))
                // The contact is the user's own, put there by code; the recipient is the job.
                .andExpect(jsonPath("$.content.sender.full_name").value(s.candidate().name()))
                .andExpect(jsonPath("$.content.sender.email").value("jordan.ikeji@example.test"))
                .andExpect(jsonPath("$.content.recipient.job_title").value("Staff Backend Engineer"))
                .andExpect(jsonPath("$.content.recipient.company").value("Acme Test Co"))
                .andExpect(jsonPath("$.content.salutation").value("Dear Hiring Manager,"))
                .andExpect(jsonPath("$.content.paragraphs.length()").value(3))
                .andExpect(jsonPath("$.content.closing").value("Yours sincerely,"))
                .andExpect(jsonPath("$.options.tone").value("FORMAL"))
                .andExpect(jsonPath("$.options.length").value("STANDARD"))
                .andExpect(jsonPath("$.changes.length()").value(0))
                .andExpect(jsonPath("$.factCheck.passed").value(true));
    }

    @Test
    void theRequestToAiServiceCarriesTheOptionsTheResumeAndTheJob() throws Exception {
        Setup s = setup();
        saveProfile(s.candidate().userId(), 8, "Lagos, Nigeria", 6_500_000, false);
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));

        coverLetter(s.session(), s.job(),
                "{\"tone\":\"WARM\",\"length\":\"SHORT\",\"notes\":\"Mention my open-source work\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.options.tone").value("WARM"))
                .andExpect(jsonPath("$.options.length").value("SHORT"))
                .andExpect(jsonPath("$.options.notes").value("Mention my open-source work"));

        JsonNode sent = mapper.readTree(letterRequests(s.candidate().userId()).get(0).getBodyAsString());
        assertThat(sent.get("prompt_version").asString()).isEqualTo("cover_letter/v1");
        assertThat(sent.get("tone").asString()).isEqualTo("warm");
        assertThat(sent.get("length").asString()).isEqualTo("short");
        assertThat(sent.get("notes").asString()).isEqualTo("Mention my open-source work");
        assertThat(sent.get("years_experience").asInt()).isEqualTo(8);
        assertThat(sent.get("as_of").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(sent.get("job").get("title").asString()).isEqualTo("Staff Backend Engineer");
        assertThat(sent.get("job").get("company").asString()).isEqualTo("Acme Test Co");
        assertThat(sent.get("job").get("description").asString()).contains("Kafka");
        assertThat(sent.get("resume").get("contact").get("full_name").asString()).isEqualTo(s.candidate().name());
    }

    @Test
    void anUnknownToneOrLengthOrTooLongNotesAreRejected() throws Exception {
        Setup s = setup();

        coverLetter(s.session(), s.job(), "{\"tone\":\"SHOUTY\"}").andExpect(status().isBadRequest());
        coverLetter(s.session(), s.job(), "{\"length\":\"EPIC\"}").andExpect(status().isBadRequest());
        coverLetter(s.session(), s.job(), "{\"notes\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(letterRequests(s.candidate().userId())).isEmpty();
        assertThat(documents(s.candidate().userId())).isZero();
    }

    @Test
    void theSameOptionsAgainReturnTheOpenDraftWithoutAnotherCall() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));

        String first = idOf(coverLetter(s.session(), s.job(), "{\"tone\":\"WARM\"}").andExpect(status().isCreated()));
        String second = idOf(coverLetter(s.session(), s.job(), "{\"tone\":\"WARM\"}").andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(letterRequests(s.candidate().userId())).hasSize(1);
        assertThat(documents(s.candidate().userId())).isEqualTo(1);
    }

    @Test
    void otherOptionsMakeANewDraftAndKeepTheOldOneAsHistory() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));
        String first = idOf(coverLetter(s.session(), s.job(), "{\"tone\":\"FORMAL\"}").andExpect(status().isCreated()));
        // The stub answers again with another call id (usage is recorded once per call id).
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));

        String second = idOf(coverLetter(s.session(), s.job(), "{\"tone\":\"CONCISE\",\"length\":\"SHORT\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.options.tone").value("CONCISE")));

        assertThat(second).isNotEqualTo(first);
        assertThat(statusOf(first)).isEqualTo("SUPERSEDED");
        assertThat(statusOf(second)).isEqualTo("DRAFT");
        assertThat(letterRequests(s.candidate().userId())).hasSize(2);
        // The history is kept and readable, but left out of the default list and not editable.
        getDocument(s.session(), first).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUPERSEDED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/documents")
                .param("jobId", s.job().toString()).param("type", "COVER_LETTER")
                .header("Authorization", bearer(s.session()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(second));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/documents")
                .param("jobId", s.job().toString()).param("status", "SUPERSEDED")
                .header("Authorization", bearer(s.session()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(first));
        patchAs(s.session(), first, edit(2, "salutation", "Dear team,")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_superseded"));
        approve(s.session(), first).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_superseded"));
    }

    @Test
    void anApprovedLetterIsNeverOverwrittenByARegeneration() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));
        String approved = idOf(coverLetter(s.session(), s.job(), null).andExpect(status().isCreated()));
        approve(s.session(), approved).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        String before = jdbc.queryForObject("select content::text from generated_documents where id = ?::uuid",
                String.class, approved);
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));

        String again = idOf(coverLetter(s.session(), s.job(), "{\"tone\":\"WARM\"}").andExpect(status().isCreated()));

        assertThat(again).isNotEqualTo(approved);
        assertThat(statusOf(approved)).isEqualTo("APPROVED");
        assertThat(statusOf(again)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select content::text from generated_documents where id = ?::uuid",
                String.class, approved)).isEqualTo(before);
        assertThat(documents(s.candidate().userId())).isEqualTo(2);
    }

    @Test
    void aLetterThatInventsAnEmployerIsAFactCheckFailureThatCannotBeApproved() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), letterBlocked(s.candidate(), UUID.randomUUID()));

        String id = idOf(coverLetter(s.session(), s.job(), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FACT_CHECK_FAILED"))
                .andExpect(jsonPath("$.factCheck.passed").value(false))
                .andExpect(jsonPath("$.factCheck.blocking").value(1))
                .andExpect(jsonPath("$.factCheck.flags[0].code").value("NEW_EMPLOYER"))
                .andExpect(jsonPath("$.factCheck.flags[0].path").value("paragraphs[1]"))
                .andExpect(jsonPath("$.factCheck.flags[0].value").value("Zentrix Dynamics")));

        approve(s.session(), id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("fact_check_failed")).andExpect(jsonPath("$.blocking").value(1));
        assertThat(statusOf(id)).isEqualTo("FACT_CHECK_FAILED");
    }

    @Test
    void anInjectedJobPostingIsFlaggedAsAWarningAndTheLetterStaysClean() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        stubTextCheck(candidate);
        UUID job = injectedJob();
        // What ai-service returns for this posting: the instruction removed and reported, the letter unaffected.
        stubLetter(candidate.userId(), letterOk(candidate, UUID.randomUUID(), "0.003"));

        String id = idOf(coverLetter(me, job, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.factCheck.flags[0].code").value("JOB_DESCRIPTION_INJECTION"))
                .andExpect(jsonPath("$.factCheck.flags[0].severity").value("WARNING")));

        // The flag quotes the offending sentence for the reviewer; the letter's own content must stay clean.
        String letter = JsonPath.<Object>read(body(getDocument(me, id)), "$.content").toString();
        assertThat(letter).doesNotContain("Zentrix").doesNotContain("PhD").doesNotContain("CTO");
        // The posting goes to ai-service as untrusted data (it delimits and scrubs it there), never as a prompt here.
        String sent = letterRequests(candidate.userId()).get(0).getBodyAsString();
        assertThat(JsonPath.<String>read(sent, "$.job.description")).contains("Ignore all previous instructions");
        // A warning does not stop approval.
        approve(me, id).andExpect(status().isOk());
    }

    @Test
    void aPlaceholderInTheResponseIsNeverStoredAndTheCallIsRecordedAsFailed() throws Exception {
        Setup s = setup();
        UUID callId = UUID.randomUUID();
        ObjectNode bad = withParagraph(letterOk(s.candidate(), callId, "0.003"), 0,
                "Dear [Your Name], I am applying for this role.");
        stubLetter(s.candidate().userId(), bad);

        coverLetter(s.session(), s.job(), null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("writing_unavailable"));

        assertThat(documents(s.candidate().userId())).isZero();
        assertThat(callStatus(callId)).isEqualTo("FAILED");
        assertThat(featureOf(callId)).isEqualTo("cover_letter");
    }

    @Test
    void aNeedsInputMarkerOrAnEmptyLetterIsNeverStoredEither() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(),
                withParagraph(letterOk(s.candidate(), UUID.randomUUID(), "0.003"), 1, "NEEDS_INPUT"));
        coverLetter(s.session(), s.job(), null).andExpect(status().isServiceUnavailable());

        ObjectNode empty = letterOk(s.candidate(), UUID.randomUUID(), "0.003");
        ((ObjectNode) empty.get("letter")).putArray("paragraphs");
        stubLetter(s.candidate().userId(), empty);
        coverLetter(s.session(), s.job(), "{\"tone\":\"WARM\"}").andExpect(status().isServiceUnavailable());

        assertThat(documents(s.candidate().userId())).isZero();
    }

    @Test
    void aResponseMadeWithAnotherPromptIsRefused() throws Exception {
        Setup s = setup();
        ObjectNode other = letterOk(s.candidate(), UUID.randomUUID(), "0.003");
        other.put("prompt_version", "cover_letter/v9");
        stubLetter(s.candidate().userId(), other);

        coverLetter(s.session(), s.job(), null).andExpect(status().isServiceUnavailable());

        assertThat(documents(s.candidate().userId())).isZero();
    }

    @Test
    void usageIsRecordedUnderTheCoverLetterFeatureForTheCaller() throws Exception {
        Setup s = setup();
        UUID callId = UUID.randomUUID();
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), callId, "0.003"));

        coverLetter(s.session(), s.job(), null).andExpect(status().isCreated());

        Map<String, Object> call = jdbc.queryForMap("select * from ai_calls where request_key = ?",
                "ai-service:" + callId);
        assertThat(call).containsEntry("user_id", s.candidate().userId()).containsEntry("feature", "cover_letter")
                .containsEntry("cost_micro_usd", 3_000L).containsEntry("status", "SUCCEEDED");
        assertThat(countRows("credit_ledger", s.candidate().userId())).isEqualTo(1);
    }

    @Test
    void anExhaustedDailyCapBlocksWithTheTypedErrorBeforeAnyCall() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), s.candidate().userId(), "parse_resume", "test", "m", 1,
                1, new BigDecimal("5.00"), 1, "p/v1", "test", AiCallStatus.SUCCEEDED));

        coverLetter(s.session(), s.job(), null).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ai_daily_cap_reached"))
                .andExpect(jsonPath("$.resetsAt").isString()).andExpect(header().exists("Retry-After"));

        assertThat(letterRequests(s.candidate().userId())).isEmpty();
        assertThat(documents(s.candidate().userId())).isZero();
    }

    @Test
    void whenAiServiceIsDownTheAnswerIs503AndNoPlaceholderIsLeftBehind() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), 503, "{\"code\":\"llm_unavailable\"}");

        coverLetter(s.session(), s.job(), null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("writing_unavailable"));

        assertThat(documents(s.candidate().userId())).isZero();
        // And the next try is free to go through.
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));
        coverLetter(s.session(), s.job(), null).andExpect(status().isCreated());
    }

    @Test
    void aDoubleClickMakesOneLetterAndOneModelCall() throws Exception {
        Setup s = setup();
        stubSlowLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"), 800);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Map.Entry<Integer, String>> click = () -> {
                var response = coverLetter(s.session(), s.job(), null).andReturn().getResponse();
                return Map.entry(response.getStatus(), JsonPath.<String>read(response.getContentAsString(), "$.id"));
            };
            Future<Map.Entry<Integer, String>> a = pool.submit(click);
            Future<Map.Entry<Integer, String>> b = pool.submit(click);
            var first = a.get();
            var second = b.get();

            assertThat(Set.of(first.getKey(), second.getKey())).containsExactlyInAnyOrder(200, 201);
            assertThat(first.getValue()).isEqualTo(second.getValue());
        } finally {
            pool.shutdownNow();
        }
        assertThat(letterRequests(s.candidate().userId())).hasSize(1);
        assertThat(documents(s.candidate().userId())).isEqualTo(1);
    }

    @Test
    void anotherUsersLetterIsNotFoundForReadEditApproveAndDelete() throws Exception {
        Setup s = setup();
        stubLetter(s.candidate().userId(), letterOk(s.candidate(), UUID.randomUUID(), "0.003"));
        String id = idOf(coverLetter(s.session(), s.job(), null).andExpect(status().isCreated()));
        Session other = newSession();
        seed(other);

        getDocument(other, id).andExpect(status().isNotFound());
        patchAs(other, id, edit(1, "salutation", "Dear team,")).andExpect(status().isNotFound());
        approve(other, id).andExpect(status().isNotFound());
        deleteDocument(other, id).andExpect(status().isNotFound());
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void anotherUsersJobIsStillThatUsersOwnToWriteFor() throws Exception {
        // Jobs are shared data (the feed); the letter is made from the caller's own resume and belongs to the caller.
        Setup mine = setup();
        Session other = newSession();
        Candidate theirs = seed(other);
        stubTextCheck(theirs);
        stubLetter(mine.candidate().userId(), letterOk(mine.candidate(), UUID.randomUUID(), "0.003"));
        stubLetter(theirs.userId(), letterOk(theirs, UUID.randomUUID(), "0.003"));

        String a = idOf(coverLetter(mine.session(), mine.job(), null).andExpect(status().isCreated()));
        String b = idOf(coverLetter(other, mine.job(), null).andExpect(status().isCreated()));

        assertThat(a).isNotEqualTo(b);
        assertThat(body(getDocument(other, b))).contains(theirs.name()).doesNotContain(mine.candidate().name());
    }
}
