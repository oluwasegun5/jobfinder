package com.jobfinder.core.applications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.AiUsageLedger;

/**
 * The AI follow-up email draft (docs/adr/0032-application-tracker.md): a draft is returned and nothing else happens (it
 * is never sent, never stored), the model is told only what the tracker knows, the call is metered and capped, and
 * every way it can fail is a typed error.
 */
class FollowUpDraftTests extends ApplicationsTestSupport {

    @Autowired
    private AiUsageLedger ledger;

    private record Setup(Session session, Candidate candidate, String app) {
    }

    private Setup setup() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        return new Setup(me, candidate, manual(me, "Backend Engineer"));
    }

    @Test
    void itRequiresTheOwnerOfTheApplication() throws Exception {
        Setup s = setup();
        Session other = newSession();
        seed(other);

        draft(other, s.app(), "{}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("application_not_found"));
        draft(s.session(), UUID.randomUUID().toString(), "{}").andExpect(status().isNotFound());
    }

    @Test
    void aDraftIsReturnedWithItsSubjectAndBodyAndNothingIsSentOrStored() throws Exception {
        Setup s = setup();
        UUID callId = UUID.randomUUID();
        stubFollowUp(s.candidate().userId(), followUpOk(callId, "0.004"));
        String address = emailOf(s.candidate().userId());
        int mailsBefore = mails(address).size();

        draft(s.session(), s.app(), "{\"tone\":\"WARM\",\"length\":\"STANDARD\",\"notes\":\"I enjoyed meeting Sam\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.subject").isString())
                .andExpect(jsonPath("$.body").isString()).andExpect(jsonPath("$.tone").value("WARM"))
                .andExpect(jsonPath("$.length").value("STANDARD")).andExpect(jsonPath("$.model").isString())
                .andExpect(jsonPath("$.promptVersion").isString());

        assertThat(mails(address)).hasSize(mailsBefore);
        assertThat(rows("reminders", s.candidate().userId())).isZero();
        assertThat(documents(s.candidate().userId())).isZero();
        // the application is exactly as it was
        getAs(s.session(), "/applications/" + s.app()).andExpect(jsonPath("$.events.length()").value(1));
    }

    @Test
    void theModelIsToldTheApplicationAndTheRequestNotesButNotThePrivateNotes() throws Exception {
        Setup s = setup();
        UUID userId = s.candidate().userId();
        stubFollowUp(userId, followUpOk(UUID.randomUUID(), "0.004"));
        patchApp(s.session(), s.app(), "{\"notes\":\"PRIVATE-NOTE recruiter was rude\"}").andExpect(status().isOk());

        draft(s.session(), s.app(), "{\"notes\":\"I enjoyed meeting Sam\",\"tone\":\"CONCISE\"}")
                .andExpect(status().isOk());

        String sent = followUpRequests(userId).get(0).getBodyAsString();
        assertThat(sent).contains("Backend Engineer").contains("Acme").contains("I enjoyed meeting Sam")
                .contains(s.candidate().name()).contains("\"APPLIED\"").doesNotContain("PRIVATE-NOTE");
    }

    @Test
    void anApplicationFromAJobSendsItsDescriptionToo() throws Exception {
        Session me = newSession();
        Candidate candidate = seed(me);
        UUID job = newJob();
        String app = fromJob(me, job);
        stubFollowUp(candidate.userId(), followUpOk(UUID.randomUUID(), "0.004"));

        draft(me, app, "{}").andExpect(status().isOk());

        assertThat(followUpRequests(candidate.userId()).get(0).getBodyAsString()).contains("Kafka");
    }

    @Test
    void theCallIsMeteredAsTheFollowUpFeature() throws Exception {
        Setup s = setup();
        UUID callId = UUID.randomUUID();
        stubFollowUp(s.candidate().userId(), followUpOk(callId, "0.004"));

        draft(s.session(), s.app(), "{}").andExpect(status().isOk());

        Map<String, Object> call = jdbc.queryForMap("select * from ai_calls where request_key = ?",
                "ai-service:" + callId);
        assertThat(call).containsEntry("user_id", s.candidate().userId()).containsEntry("feature", "follow_up_email")
                .containsEntry("cost_micro_usd", 4_000L).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void anExhaustedDailyCapBlocksWithTheTypedErrorBeforeAnyCall() throws Exception {
        Setup s = setup();
        UUID userId = s.candidate().userId();
        stubFollowUp(userId, followUpOk(UUID.randomUUID(), "0.004"));
        com.jobfinder.core.TestCredits.seed(jdbc, userId);
        ledger.record(new AiUsage("test:" + UUID.randomUUID(), userId, "parse_resume", "test", "m", 1, 1,
                new BigDecimal("5.00"), 1, "p/v1", "test", AiCallStatus.SUCCEEDED));

        draft(s.session(), s.app(), "{}").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ai_daily_cap_reached")).andExpect(jsonPath("$.resetsAt").isString())
                .andExpect(header().exists("Retry-After"));

        assertThat(followUpRequests(userId)).isEmpty();
    }

    @Test
    void whenAiServiceIsDownTheAnswerIsATyped503() throws Exception {
        Setup s = setup();
        stubFollowUp(s.candidate().userId(), 503, "{\"code\":\"llm_unavailable\"}");

        draft(s.session(), s.app(), "{}").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("follow_up_unavailable"));

        // and the next try goes through
        stubFollowUp(s.candidate().userId(), followUpOk(UUID.randomUUID(), "0.004"));
        draft(s.session(), s.app(), "{}").andExpect(status().isOk());
    }

    @Test
    void aDraftThatInventsAnEmployerIsDiscardedButItsCostIsStillRecorded() throws Exception {
        Setup s = setup();
        UUID callId = UUID.randomUUID();
        stubFollowUp(s.candidate().userId(), followUpBlocked(callId));

        draft(s.session(), s.app(), "{}").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("follow_up_rejected"))
                .andExpect(jsonPath("$.body").doesNotExist());

        assertThat(count("select count(*) from ai_calls where request_key = ?", "ai-service:" + callId)).isEqualTo(1);
    }

    @Test
    void anApplicationNotMadeYetHasNothingToFollowUp() throws Exception {
        Setup s = setup();
        UUID job = newJob();
        String saved = idOf(create(s.session(), "{\"jobId\":\"" + job + "\",\"status\":\"SAVED\"}"));

        draft(s.session(), saved, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_applied_yet"));
        assertThat(followUpRequests(s.candidate().userId())).isEmpty();
    }

    @Test
    void aResumeIsRequired() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");

        draft(me, app, "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("resume_required"));
    }

    @Test
    void theRequestIsValidated() throws Exception {
        Setup s = setup();

        draft(s.session(), s.app(), "{\"tone\":\"ANGRY\"}").andExpect(status().isBadRequest());
        draft(s.session(), s.app(), "{\"notes\":\"" + "x".repeat(1001) + "\"}").andExpect(status().isBadRequest());
    }
}
