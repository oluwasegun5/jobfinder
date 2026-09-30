package com.jobfinder.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.jobfinder.core.identity.AuthTestSupport;
import com.jobfinder.core.identity.internal.GoogleTokenVerifier.GoogleIdentity;

class GoogleLoginTests extends AuthTestSupport {

    @MockitoBean
    private GoogleTokenVerifier google;

    private static String body(String token) {
        return "{\"idToken\":\"%s\"}".formatted(token);
    }

    private String tokenFor(String sub, String email, boolean emailVerified) {
        String token = "tok-" + UUID.randomUUID();
        when(google.verify(token)).thenReturn(new GoogleIdentity(sub, email, emailVerified));
        return token;
    }

    private static String newSub() {
        return "sub-" + UUID.randomUUID();
    }

    @Test
    void firstGoogleSignInCreatesAVerifiedPasswordlessAccountAndIssuesTokens() throws Exception {
        String email = newEmail();
        String token = tokenFor(newSub(), email, true);

        Session session = sessionFrom(postJson("/auth/google", body(token), newIp())
                .andExpect(status().isOk()).andReturn().getResponse());

        getMe(session.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.role").value("USER"));
        assertThat(count("select count(*) from users where email = ? and password_hash is null", email)).isEqualTo(1);
        assertThat(count("select count(*) from oauth_accounts o join users u on u.id = o.user_id "
                + "where u.email = ?", email)).isEqualTo(1);
        postWithCookie("/auth/refresh", session.refreshToken(), newIp()).andExpect(status().isOk());
    }

    @Test
    void googleSignInLinksToAnExistingVerifiedEmailAccountWithoutTouchingItsPassword() throws Exception {
        String email = registerVerifiedUser();
        UUID id = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        String token = tokenFor(newSub(), email, true);

        postJson("/auth/google", body(token), newIp()).andExpect(status().isOk());

        assertThat(count("select count(*) from users where email = ?", email)).isEqualTo(1);
        assertThat(count("select count(*) from oauth_accounts where user_id = ?", id)).isEqualTo(1);
        login(email, PASSWORD, newIp());
    }

    @Test
    void googleSignInIsMatchedBySubjectOnLaterLogins() throws Exception {
        String sub = newSub();
        String email = newEmail();
        postJson("/auth/google", body(tokenFor(sub, email, true)), newIp()).andExpect(status().isOk());

        postJson("/auth/google", body(tokenFor(sub, email, true)), newIp()).andExpect(status().isOk());

        assertThat(count("select count(*) from users where email = ?", email)).isEqualTo(1);
        assertThat(count("select count(*) from oauth_accounts where provider_user_id = ?", sub)).isEqualTo(1);
    }

    @Test
    void linkingToAnUnverifiedPreRegisteredAccountRemovesTheSquattersPassword() throws Exception {
        String email = newEmail();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        String token = tokenFor(newSub(), email, true);

        postJson("/auth/google", body(token), newIp()).andExpect(status().isOk());

        assertThat(count("select count(*) from users where email = ? and password_hash is null "
                + "and email_verified_at is not null", email)).isEqualTo(1);
        postJson("/auth/login", credentials(email, PASSWORD), newIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void anEmailGoogleHasNotVerifiedIsNeverTrusted() throws Exception {
        String email = registerVerifiedUser();
        String token = tokenFor(newSub(), email, false);

        postJson("/auth/google", body(token), newIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_google_token"));

        assertThat(count("select count(*) from oauth_accounts o join users u on u.id = o.user_id "
                + "where u.email = ?", email)).isZero();
    }

    @Test
    void anInvalidGoogleTokenIsRejected() throws Exception {
        when(google.verify("forged")).thenThrow(AuthException.invalidGoogleToken());

        postJson("/auth/google", body("forged"), newIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_google_token"));
    }

    @Test
    void aDisabledAccountCannotSignInWithGoogle() throws Exception {
        String email = registerVerifiedUser();
        jdbc.update("update users set status = 'DISABLED' where email = ?", email);

        postJson("/auth/google", body(tokenFor(newSub(), email, true)), newIp())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBlankTokenIsABadRequest() throws Exception {
        postJson("/auth/google", body(""), newIp()).andExpect(status().isBadRequest());
    }
}
