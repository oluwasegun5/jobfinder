package com.jobfinder.core.profile.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.profile.ResumeParsingTestSupport;

/**
 * Queued parse messages outlive the state they were sent in (docs/adr/0040): one arriving after the user withdrew AI
 * consent must not reach the model, and one arriving after the account was deleted must create nothing.
 */
class ParseConsentAndDeletionTests extends ResumeParsingTestSupport {

    @Autowired
    private ResumeParseWorker worker;

    private record Parsed(Session session, UUID userId, UUID resumeId) {
    }

    private Parsed uploadedAndParsed() throws Exception {
        Session session = newSession();
        UUID userId = userIdOf(session.accessToken());
        stubParseOk(userId);
        UUID resumeId = uploadPdf(session);
        awaitStatus(resumeId, "PARSED");
        return new Parsed(session, userId, resumeId);
    }

    @Test
    void aParseMessageForAUserWhoWithdrewConsentFailsWithAReasonAndCallsNoModel() throws Exception {
        Parsed p = uploadedAndParsed();
        int before = parseRequests(p.userId());
        jdbc.update("update users set ai_consent_version = null, ai_consent_at = null where id = ?", p.userId());
        jdbc.update("update resumes set parse_status = 'PENDING', parse_error = null where id = ?", p.resumeId());

        worker.handle(new ResumeParseMessage(p.resumeId(), p.userId(), 1));

        assertThat(parseStatus(p.resumeId())).isEqualTo("FAILED");
        assertThat(parseError(p.resumeId())).isEqualTo("ai_consent_required");
        assertThat(parseRequests(p.userId())).isEqualTo(before);
    }

    @Test
    void aParseMessageForADeletedAccountIsDroppedAndCreatesNothing() throws Exception {
        Parsed p = uploadedAndParsed();
        int before = parseRequests(p.userId());
        mvc.perform(delete("/me").header("Authorization", "Bearer " + p.session().accessToken()))
                .andExpect(status().isNoContent());

        worker.handle(new ResumeParseMessage(p.resumeId(), p.userId(), 1));

        assertThat(count("select count(*) from resumes where id = ?", p.resumeId())).isZero();
        assertThat(count("select count(*) from resume_versions where resume_id = ?", p.resumeId())).isZero();
        assertThat(count("select count(*) from users where id = ?", p.userId())).isZero();
        assertThat(parseRequests(p.userId())).isEqualTo(before);
        assertThat(objectsUnder("resumes/" + p.userId() + "/")).isZero();
    }
}
