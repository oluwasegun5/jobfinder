package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class RefreshAndLogoutTests extends AuthTestSupport {

    @Test
    void refreshRotatesTheTokenAndIssuesAWorkingAccessToken() throws Exception {
        String email = registerVerifiedUser();
        Session first = login(email, PASSWORD, newIp());

        MockHttpServletResponse response = postWithCookie("/auth/refresh", first.refreshToken(), newIp())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse();

        Session second = sessionFrom(response);
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(setCookieHeader(response)).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        getMe(second.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        // Same family, exactly one live token.
        assertThat(count("select count(distinct family_id) from refresh_tokens r join users u on u.id = r.user_id "
                + "where u.email = ?", email)).isEqualTo(1);
        assertThat(count("select count(*) from refresh_tokens r join users u on u.id = r.user_id "
                + "where u.email = ? and r.revoked_at is null", email)).isEqualTo(1);
    }

    @Test
    void aRotatedTokenCannotBeUsedAgain() throws Exception {
        String email = registerVerifiedUser();
        Session first = login(email, PASSWORD, newIp());
        postWithCookie("/auth/refresh", first.refreshToken(), newIp()).andExpect(status().isOk());

        postWithCookie("/auth/refresh", first.refreshToken(), newIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_refresh_token"));
    }

    @Test
    void reuseOfARotatedTokenRevokesTheWholeFamilyIncludingTheLegitimateSuccessor() throws Exception {
        String email = registerVerifiedUser();
        Session stolen = login(email, PASSWORD, newIp());

        // The legitimate user refreshes normally: stolen token -> current token.
        Session current = sessionFrom(postWithCookie("/auth/refresh", stolen.refreshToken(), newIp())
                .andExpect(status().isOk()).andReturn().getResponse());

        // The attacker replays the old token: detected as reuse.
        postWithCookie("/auth/refresh", stolen.refreshToken(), newIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_refresh_token"));

        // The whole family is dead: the successor no longer works either, forcing a fresh login.
        postWithCookie("/auth/refresh", current.refreshToken(), newIp())
                .andExpect(status().isUnauthorized());
        assertThat(count("select count(*) from refresh_tokens r join users u on u.id = r.user_id "
                + "where u.email = ? and r.revoked_at is null", email)).isZero();

        // The user can log in again normally.
        Session fresh = login(email, PASSWORD, newIp());
        postWithCookie("/auth/refresh", fresh.refreshToken(), newIp()).andExpect(status().isOk());
    }

    @Test
    void reuseDetectionOnlyAffectsTheCompromisedFamily() throws Exception {
        String email = registerVerifiedUser();
        Session laptop = login(email, PASSWORD, newIp());
        Session phone = login(email, PASSWORD, newIp());

        Session laptopNext = sessionFrom(postWithCookie("/auth/refresh", laptop.refreshToken(), newIp())
                .andExpect(status().isOk()).andReturn().getResponse());
        postWithCookie("/auth/refresh", laptop.refreshToken(), newIp()).andExpect(status().isUnauthorized());

        postWithCookie("/auth/refresh", laptopNext.refreshToken(), newIp()).andExpect(status().isUnauthorized());
        postWithCookie("/auth/refresh", phone.refreshToken(), newIp()).andExpect(status().isOk());
    }

    @Test
    void concurrentRefreshWithTheSameTokenSucceedsExactlyOnce() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());
        int attempts = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String ip = newIp();
                Callable<Integer> attempt = () -> {
                    start.await();
                    return postWithCookie("/auth/refresh", session.refreshToken(), ip)
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 401).hasSize(attempts - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingUnknownAndExpiredRefreshTokensAreRejected() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/refresh")
                .with(request -> {
                    request.setRemoteAddr(newIp());
                    return request;
                }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_refresh_token"));

        postWithCookie("/auth/refresh", "never-issued", newIp()).andExpect(status().isUnauthorized());

        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 minute' "
                + "where user_id = (select id from users where email = ?)", email);
        postWithCookie("/auth/refresh", session.refreshToken(), newIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void aFailedRefreshClearsTheCookie() throws Exception {
        MockHttpServletResponse response = postWithCookie("/auth/refresh", "never-issued", newIp())
                .andExpect(status().isUnauthorized()).andReturn().getResponse();

        assertThat(setCookieHeader(response)).contains("Max-Age=0").contains("HttpOnly");
    }

    @Test
    void refreshIsRefusedForAccountsDisabledAfterLogin() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());
        jdbc.update("update users set status = 'DISABLED' where email = ?", email);

        postWithCookie("/auth/refresh", session.refreshToken(), newIp()).andExpect(status().isUnauthorized());
        assertThat(count("select count(*) from refresh_tokens r join users u on u.id = r.user_id "
                + "where u.email = ? and r.revoked_at is null", email)).isZero();
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());
        Session rotated = sessionFrom(postWithCookie("/auth/refresh", session.refreshToken(), newIp())
                .andExpect(status().isOk()).andReturn().getResponse());

        MockHttpServletResponse response = postWithCookie("/auth/logout", rotated.refreshToken(), newIp())
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertThat(setCookieHeader(response)).contains("Max-Age=0").contains("HttpOnly").contains("Path=/api/core/auth");
        postWithCookie("/auth/refresh", rotated.refreshToken(), newIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutIsIdempotentAndDoesNotRequireASession() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/logout"))
                .andExpect(status().isNoContent());
        postWithCookie("/auth/logout", "never-issued", newIp()).andExpect(status().isNoContent());
    }

    @Test
    void logoutOnlyEndsTheCallersOwnSession() throws Exception {
        String email = registerVerifiedUser();
        Session laptop = login(email, PASSWORD, newIp());
        Session phone = login(email, PASSWORD, newIp());

        postWithCookie("/auth/logout", laptop.refreshToken(), newIp()).andExpect(status().isNoContent());

        postWithCookie("/auth/refresh", laptop.refreshToken(), newIp()).andExpect(status().isUnauthorized());
        postWithCookie("/auth/refresh", phone.refreshToken(), newIp()).andExpect(status().isOk());
    }
}
