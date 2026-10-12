package com.jobfinder.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.jobfinder.core.activation.EmailVerified;
import com.jobfinder.core.activation.IdentityDigester;
import com.jobfinder.core.identity.AuthTestSupport;
import com.jobfinder.core.identity.internal.GoogleTokenVerifier.GoogleIdentity;

/**
 * Identity publishes milestone M1 ({@link EmailVerified}) with digests only, and a reused Google account (same subject,
 * any email) gets no second trial (docs/adr/0042-trial-credit-abuse-gate.md).
 */
@RecordApplicationEvents
@TestPropertySource(properties = {"app.billing.trial.enabled=true", "app.billing.jobs.free-grant-enabled=false"})
class TrialMilestoneEventTests extends AuthTestSupport {

    @MockitoBean
    private GoogleTokenVerifier google;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private IdentityDigester digester;

    private String googleToken(String sub, String email) {
        String token = "tok-" + UUID.randomUUID();
        when(google.verify(token)).thenReturn(new GoogleIdentity(sub, email, true));
        return token;
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private String trialState(UUID id) {
        List<String> s = jdbc.queryForList("select state from trial_accounts where user_id = ?", String.class, id);
        return s.isEmpty() ? null : s.get(0);
    }

    @Test
    void verifyingTheEmailPublishesEmailVerifiedWithTheCanonicalEmailDigest() throws Exception {
        String email = registerVerifiedUser();

        List<EmailVerified> published = events.stream(EmailVerified.class).toList();

        assertThat(published).hasSize(1);
        EmailVerified event = published.get(0);
        assertThat(event.userId()).isEqualTo(userId(email));
        assertThat(event.email().current().value()).isEqualTo(digester.email(email).orElseThrow().current().value());
        assertThat(event.emailDomain()).isEqualTo("example.test");
        assertThat(event.viaGoogle()).isFalse();
    }

    @Test
    void aFirstGoogleSignInPublishesEmailVerifiedWithTheGoogleSubject() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String email = newEmail();

        postJson("/auth/google", "{\"idToken\":\"%s\"}".formatted(googleToken(sub, email)), newIp())
                .andExpect(status().isOk());
        postJson("/auth/google", "{\"idToken\":\"%s\"}".formatted(googleToken(sub, email)), newIp())
                .andExpect(status().isOk());

        List<EmailVerified> published = events.stream(EmailVerified.class).toList();
        assertThat(published).hasSize(1); // a returning sign-in is not a new milestone
        assertThat(published.get(0).googleSubject().current().value())
                .isEqualTo(digester.googleSubject(sub).current().value());
        assertThat(trialState(userId(email))).isEqualTo("GRANTED");
    }

    @Test
    void aReusedGoogleAccountGetsNoSecondTrialEvenWithANewEmail() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String first = newEmail();
        Session session = sessionFrom(postJson("/auth/google",
                "{\"idToken\":\"%s\"}".formatted(googleToken(sub, first)), newIp())
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(trialState(userId(first))).isEqualTo("GRANTED");
        mvc.perform(delete("/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());

        String second = newEmail(); // the Google account's email changed; its subject did not
        postJson("/auth/google", "{\"idToken\":\"%s\"}".formatted(googleToken(sub, second)), newIp())
                .andExpect(status().isOk());

        assertThat(trialState(userId(second))).isEqualTo("DENIED");
        assertThat(count("select count(*) from credit_ledger where user_id = ? and reason = 'TRIAL_GRANT'",
                userId(second))).isZero();
    }
}
