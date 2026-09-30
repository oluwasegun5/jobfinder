package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class AccountDeletionTests extends AuthTestSupport {

    @Autowired
    private ApplicationEvents events;

    private UUID userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    @Test
    void deletingTheAccountPublishesUserDeletionRequestedForThatUser() throws Exception {
        String email = registerVerifiedUser();
        UUID id = userId(email);
        Session session = login(email, PASSWORD, newIp());

        mvc.perform(delete("/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());

        assertThat(events.stream(UserDeletionRequested.class))
                .singleElement()
                .satisfies(event -> assertThat(event.userId()).isEqualTo(id));
    }

    @Test
    void deletionRemovesAllIdentityData() throws Exception {
        String email = registerVerifiedUser();
        UUID id = userId(email);
        Session session = login(email, PASSWORD, newIp());
        jdbc.update("insert into oauth_accounts (id, user_id, provider, provider_user_id, created_at, updated_at) "
                + "values (?, ?, 'GOOGLE', ?, now(), now())", UUID.randomUUID(), id, "sub-" + id);

        MockHttpServletResponse response = mvc
                .perform(delete("/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertThat(setCookieHeader(response)).contains("Max-Age=0");
        assertThat(count("select count(*) from users where id = ?", id)).isZero();
        assertThat(count("select count(*) from refresh_tokens where user_id = ?", id)).isZero();
        assertThat(count("select count(*) from email_tokens where user_id = ?", id)).isZero();
        assertThat(count("select count(*) from oauth_accounts where user_id = ?", id)).isZero();
    }

    @Test
    void aDeletedAccountCannotLogInOrRefreshAgain() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());
        mvc.perform(delete("/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());

        postJson("/auth/login", credentials(email, PASSWORD), newIp()).andExpect(status().isUnauthorized());
        postWithCookie("/auth/refresh", session.refreshToken(), newIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void deletionOnlyAffectsTheCaller() throws Exception {
        String victimEmail = registerVerifiedUser();
        UUID victimId = userId(victimEmail);
        login(victimEmail, PASSWORD, newIp());
        String callerEmail = registerVerifiedUser();
        Session caller = login(callerEmail, PASSWORD, newIp());

        mvc.perform(delete("/me").header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from users where id = ?", victimId)).isEqualTo(1);
        assertThat(count("select count(*) from refresh_tokens where user_id = ?", victimId)).isEqualTo(1);
        assertThat(events.stream(UserDeletionRequested.class)).noneMatch(e -> e.userId().equals(victimId));
    }

    @Test
    void deletionRequiresAuthentication() throws Exception {
        mvc.perform(delete("/me")).andExpect(status().isUnauthorized());
        assertThat(events.stream(UserDeletionRequested.class)).isEmpty();
    }
}
