package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.CoversEndpoints;
import com.jobfinder.core.billing.AiConsentRequiredException;
import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.billing.GateStatus;
import com.jobfinder.core.profile.ResumeTestSupport;

/**
 * Consent to AI processing (docs/adr/0040): required at signup, recorded with a version and a time, changeable by the
 * user alone, and enforced where every AI call passes (the billing gate) and before a CV is stored.
 */
class AiConsentTests extends ResumeTestSupport {

    @Autowired
    private AiUsageGate gate;

    @Autowired
    private AiConsent consent;

    private UUID idOf(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private static String signupBody(String email, String consentField) {
        return "{\"email\":\"%s\",\"password\":\"%s\"%s}".formatted(email, PASSWORD, consentField);
    }

    @Test
    void signupWithoutTheConsentIsRefusedAndCreatesNothing() throws Exception {
        String missing = newEmail();
        String declined = newEmail();

        postJson("/auth/signup", signupBody(missing, ""), newIp()).andExpect(status().isBadRequest());
        postJson("/auth/signup", signupBody(declined, ",\"aiProcessingConsent\":false"), newIp())
                .andExpect(status().isBadRequest());

        assertThat(count("select count(*) from users where email in (?, ?)", missing, declined)).isZero();
    }

    @Test
    void signupWithTheConsentRecordsTheVersionAndTheTime() throws Exception {
        String email = newEmail();

        postJson("/auth/signup", signupBody(email, ",\"aiProcessingConsent\":true"), newIp())
                .andExpect(status().isAccepted());

        UUID id = idOf(email);
        assertThat(jdbc.queryForObject("select ai_consent_version from users where id = ?", String.class, id))
                .isNotBlank();
        assertThat(jdbc.queryForObject("select ai_consent_at is not null from users where id = ?", Boolean.class, id))
                .isTrue();
        assertThat(consent.isGranted(id)).isTrue();
    }

    @Test
    void theUserCanSeeWithdrawAndGrantTheirConsent() throws Exception {
        Session session = newSession();
        String bearer = "Bearer " + session.accessToken();
        getMe(session.accessToken()).andExpect(jsonPath("$.aiConsent").value(true));

        mvc.perform(get("/me/consent").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiProcessing").value(true)).andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.currentVersion").isNotEmpty());

        mvc.perform(delete("/me/consent/ai").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiProcessing").value(false)).andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.grantedAt").doesNotExist());
        getMe(session.accessToken()).andExpect(jsonPath("$.aiConsent").value(false));

        mvc.perform(put("/me/consent/ai").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiProcessing").value(true)).andExpect(jsonPath("$.grantedAt").isNotEmpty());
        getMe(session.accessToken()).andExpect(jsonPath("$.aiConsent").value(true));
    }

    @CoversEndpoints({ "GET /me/consent", "PUT /me/consent/ai", "DELETE /me/consent/ai" })
    @Test
    void changingTheConsentOnlyAffectsTheCaller() throws Exception {
        Session bystander = newSession();
        Session caller = newSession();
        UUID bystanderId = userIdOf(bystander.accessToken());
        UUID callerId = userIdOf(caller.accessToken());

        mvc.perform(delete("/me/consent/ai").header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk());

        assertThat(consent.isGranted(callerId)).isFalse();
        assertThat(consent.isGranted(bystanderId)).isTrue();
        mvc.perform(get("/me/consent").header("Authorization", "Bearer " + bystander.accessToken()))
                .andExpect(jsonPath("$.aiProcessing").value(true));
    }

    @Test
    void theConsentEndpointsNeedASignedInUser() throws Exception {
        mvc.perform(get("/me/consent")).andExpect(status().isUnauthorized());
        mvc.perform(put("/me/consent/ai")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/me/consent/ai")).andExpect(status().isUnauthorized());
    }

    @Test
    void theGateRefusesEveryAiCallUntilTheUserHasAgreedAndAfterTheyWithdraw() throws Exception {
        Session session = newSession();
        UUID id = userIdOf(session.accessToken());
        assertThatCode(() -> gate.requireAllowance(id, "parse_resume")).doesNotThrowAnyException();
        assertThat(gate.status(id)).isEqualTo(GateStatus.OK);

        mvc.perform(delete("/me/consent/ai").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> gate.requireAllowance(id, "tailor_resume"))
                .isInstanceOfSatisfying(AiConsentRequiredException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("ai_consent_required");
                });
        assertThat(gate.status(id)).isEqualTo(GateStatus.CONSENT_REQUIRED);

        mvc.perform(put("/me/consent/ai").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
        assertThatCode(() -> gate.requireAllowance(id, "tailor_resume")).doesNotThrowAnyException();
    }

    @Test
    void anAccountWithoutConsentCannotUploadACvAndNothingIsStored() throws Exception {
        Session session = newSession();
        UUID id = userIdOf(session.accessToken());
        // An account created before consent was recorded (or with Google): it starts without.
        jdbc.update("update users set ai_consent_version = null, ai_consent_at = null where id = ?", id);

        upload(session, "cv.pdf", "application/pdf", pdf()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ai_consent_required"));

        assertThat(count("select count(*) from resumes where user_id = ?", id)).isZero();
        assertThat(objectsUnder("resumes/" + id + "/")).isZero();

        mvc.perform(put("/me/consent/ai").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
        upload(session, "cv.pdf", "application/pdf", pdf()).andExpect(status().isCreated());
    }
}
