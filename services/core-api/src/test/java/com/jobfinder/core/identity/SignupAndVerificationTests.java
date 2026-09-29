package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SignupAndVerificationTests extends AuthTestSupport {

    @Test
    void signupCreatesUnverifiedUserAndEmailsAVerificationLink() throws Exception {
        String email = newEmail();

        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andExpect(status().isAccepted());

        assertThat(count("select count(*) from users where email = ? and email_verified_at is null", email))
                .isEqualTo(1);
        List<String> mails = awaitMails(email, 1);
        assertThat(mails.get(0)).contains("/verify-email?token=");
    }

    @Test
    void passwordAndVerificationTokenAreNeverStoredInPlainText() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        String token = extractToken(awaitMails(email, 1).get(0));

        String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(count("select count(*) from email_tokens where token_hash = ?", token)).isZero();
        assertThat(count("select count(*) from email_tokens where type = 'VERIFY_EMAIL' and length(token_hash) = 64"))
                .isPositive();
    }

    @Test
    void emailIsCaseInsensitiveAndSignupForAnExistingAddressLooksIdenticalAndCreatesNothing() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andExpect(status().isAccepted());
        awaitMails(email, 1);

        postJson("/auth/signup", credentials(email.toUpperCase(), PASSWORD), newIp())
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(count("select count(*) from users where email = ?", email)).isEqualTo(1);
        // The owner is told about the attempt instead of receiving a second verification link.
        List<String> mails = awaitMails(email, 2);
        assertThat(mails.get(0)).contains("already has an account").doesNotContain("token=");
    }

    @Test
    void invalidSignupInputIsRejectedWithAProblemDetail() throws Exception {
        postJson("/auth/signup", credentials("not-an-email", PASSWORD), newIp())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        postJson("/auth/signup", credentials(newEmail(), "short"), newIp())
                .andExpect(status().isBadRequest());

        String tooManyBytes = "é".repeat(40); // 40 chars but 80 UTF-8 bytes: beyond what BCrypt can hash
        postJson("/auth/signup", credentials(newEmail(), tooManyBytes), newIp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("weak_password"));
    }

    @Test
    void loginIsBlockedUntilTheEmailIsVerified() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();

        postJson("/auth/login", credentials(email, PASSWORD), newIp())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_not_verified"));

        // A wrong password must not reveal that the account is unverified.
        postJson("/auth/login", credentials(email, "wrong-password-123"), newIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void verifyingTheEmailActivatesTheAccountAndTheTokenIsSingleUse() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        String token = extractToken(awaitMails(email, 1).get(0));
        String body = "{\"token\":\"%s\"}".formatted(token);

        postJson("/auth/verify-email", body, newIp()).andExpect(status().isNoContent());

        assertThat(count("select count(*) from users where email = ? and email_verified_at is not null", email))
                .isEqualTo(1);
        login(email, PASSWORD, newIp());
        postJson("/auth/verify-email", body, newIp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    @Test
    void unknownAndExpiredVerificationTokensAreRejected() throws Exception {
        postJson("/auth/verify-email", "{\"token\":\"does-not-exist\"}", newIp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_token"));

        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        String token = extractToken(awaitMails(email, 1).get(0));
        jdbc.update("update email_tokens set expires_at = now() - interval '1 minute' "
                + "where user_id = (select id from users where email = ?)", email);

        postJson("/auth/verify-email", "{\"token\":\"%s\"}".formatted(token), newIp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_token"));
        assertThat(count("select count(*) from users where email = ? and email_verified_at is not null", email))
                .isZero();
    }

    @Test
    void aResetTokenCannotBeUsedToVerifyAnEmail() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        awaitMails(email, 1);
        postJson("/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email), newIp()).andReturn();
        String resetToken = extractToken(awaitMails(email, 2).get(0));

        postJson("/auth/verify-email", "{\"token\":\"%s\"}".formatted(resetToken), newIp())
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendingVerificationIssuesANewLinkAndVoidsTheOldOne() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        String first = extractToken(awaitMails(email, 1).get(0));

        postJson("/auth/resend-verification", "{\"email\":\"%s\"}".formatted(email), newIp())
                .andExpect(status().isAccepted());
        String second = extractToken(awaitMails(email, 2).get(0));

        assertThat(second).isNotEqualTo(first);
        postJson("/auth/verify-email", "{\"token\":\"%s\"}".formatted(first), newIp())
                .andExpect(status().isBadRequest());
        postJson("/auth/verify-email", "{\"token\":\"%s\"}".formatted(second), newIp())
                .andExpect(status().isNoContent());
    }

    @Test
    void resendForUnknownOrAlreadyVerifiedAddressesLooksTheSameAndSendsNothing() throws Exception {
        postJson("/auth/resend-verification", "{\"email\":\"%s\"}".formatted(newEmail()), newIp())
                .andExpect(status().isAccepted());

        String verified = registerVerifiedUser();
        postJson("/auth/resend-verification", "{\"email\":\"%s\"}".formatted(verified), newIp())
                .andExpect(status().isAccepted());
        Thread.sleep(500); // give an (unwanted) async email time to arrive
        assertThat(mailsTo(verified)).hasSize(1);
    }
}
