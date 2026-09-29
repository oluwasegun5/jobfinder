package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class PasswordResetTests extends AuthTestSupport {

    private static final String NEW_PASSWORD = "a-brand-new-passphrase";

    private String requestReset(String email) throws Exception {
        int before = mailsTo(email).size();
        postJson("/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email), newIp())
                .andExpect(status().isAccepted());
        return extractToken(awaitMails(email, before + 1).get(0));
    }

    private static String resetBody(String token, String password) {
        return "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, password);
    }

    @Test
    void forgotPasswordEmailsALinkAndResetChangesThePassword() throws Exception {
        String email = registerVerifiedUser();
        String token = requestReset(email);
        assertThat(count("select count(*) from email_tokens where token_hash = ?", token)).isZero();

        postJson("/auth/reset-password", resetBody(token, NEW_PASSWORD), newIp())
                .andExpect(status().isNoContent());

        login(email, NEW_PASSWORD, newIp());
        postJson("/auth/login", credentials(email, PASSWORD), newIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPasswordLooksIdenticalForUnknownAddresses() throws Exception {
        String known = registerVerifiedUser();
        String knownBody = postJson("/auth/forgot-password", "{\"email\":\"%s\"}".formatted(known), newIp())
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknownBody = postJson("/auth/forgot-password", "{\"email\":\"%s\"}".formatted(newEmail()), newIp())
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(knownBody).isEqualTo(unknownBody);
    }

    @Test
    void aResetTokenIsSingleUse() throws Exception {
        String email = registerVerifiedUser();
        String token = requestReset(email);
        postJson("/auth/reset-password", resetBody(token, NEW_PASSWORD), newIp()).andExpect(status().isNoContent());

        postJson("/auth/reset-password", resetBody(token, "yet-another-passphrase"), newIp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_token"));
        login(email, NEW_PASSWORD, newIp());
    }

    @Test
    void expiredAndUnknownResetTokensAreRejected() throws Exception {
        postJson("/auth/reset-password", resetBody("does-not-exist", NEW_PASSWORD), newIp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_token"));

        String email = registerVerifiedUser();
        String token = requestReset(email);
        jdbc.update("update email_tokens set expires_at = now() - interval '1 minute' "
                + "where type = 'RESET_PASSWORD' and user_id = (select id from users where email = ?)", email);

        postJson("/auth/reset-password", resetBody(token, NEW_PASSWORD), newIp())
                .andExpect(status().isBadRequest());
        login(email, PASSWORD, newIp());
    }

    @Test
    void aNewRequestVoidsTheEarlierResetLink() throws Exception {
        String email = registerVerifiedUser();
        String first = requestReset(email);
        String second = requestReset(email);

        postJson("/auth/reset-password", resetBody(first, NEW_PASSWORD), newIp()).andExpect(status().isBadRequest());
        postJson("/auth/reset-password", resetBody(second, NEW_PASSWORD), newIp()).andExpect(status().isNoContent());
    }

    @Test
    void weakNewPasswordsAreRejectedWithoutBurningTheToken() throws Exception {
        String email = registerVerifiedUser();
        String token = requestReset(email);

        postJson("/auth/reset-password", resetBody(token, "short"), newIp()).andExpect(status().isBadRequest());

        postJson("/auth/reset-password", resetBody(token, NEW_PASSWORD), newIp()).andExpect(status().isNoContent());
    }

    @Test
    void resettingThePasswordRevokesEverySession() throws Exception {
        String email = registerVerifiedUser();
        Session laptop = login(email, PASSWORD, newIp());
        Session phone = login(email, PASSWORD, newIp());
        String token = requestReset(email);

        postJson("/auth/reset-password", resetBody(token, NEW_PASSWORD), newIp()).andExpect(status().isNoContent());

        postWithCookie("/auth/refresh", laptop.refreshToken(), newIp()).andExpect(status().isUnauthorized());
        postWithCookie("/auth/refresh", phone.refreshToken(), newIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void resetProvesMailboxOwnershipSoUnverifiedAccountsBecomeVerified() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        awaitMails(email, 1);
        String token = requestReset(email);

        postJson("/auth/reset-password", resetBody(token, NEW_PASSWORD), newIp()).andExpect(status().isNoContent());

        login(email, NEW_PASSWORD, newIp());
    }

    @Test
    void disabledAccountsGetNoResetEmail() throws Exception {
        String email = registerVerifiedUser();
        jdbc.update("update users set status = 'DISABLED' where email = ?", email);
        int before = mailsTo(email).size();

        postJson("/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email), newIp())
                .andExpect(status().isAccepted());

        Thread.sleep(500);
        assertThat(mailsTo(email)).hasSize(before);
    }
}
