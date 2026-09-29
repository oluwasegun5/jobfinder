package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Runs against the default limits: login 20 per IP and 10 per email per 15 min; signup 5 per IP per hour; ... */
class RateLimitTests extends AuthTestSupport {

    @Test
    void loginIsThrottledPerIpAddress() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 20; i++) {
            // A different email each time so only the per-IP bucket fills.
            postJson("/auth/login", credentials(newEmail(), PASSWORD), ip).andExpect(status().isUnauthorized());
        }

        MockHttpServletResponse blocked = postJson("/auth/login", credentials(newEmail(), PASSWORD), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"))
                .andReturn().getResponse();

        assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isPositive();
        // Other clients are unaffected.
        postJson("/auth/login", credentials(newEmail(), PASSWORD), newIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsThrottledPerAccountEvenWhenTheAttackerRotatesIps() throws Exception {
        String email = registerVerifiedUser();
        for (int i = 0; i < 10; i++) {
            postJson("/auth/login", credentials(email, "guess-number-" + i + "-xx"), newIp())
                    .andExpect(status().isUnauthorized());
        }

        postJson("/auth/login", credentials(email, PASSWORD), newIp())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void signupIsThrottledPerIpAddress() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            postJson("/auth/signup", credentials(newEmail(), PASSWORD), ip).andExpect(status().isAccepted());
        }

        postJson("/auth/signup", credentials(newEmail(), PASSWORD), ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void forgotPasswordIsThrottledPerAddressSoAMailboxCannotBeFlooded() throws Exception {
        String email = registerVerifiedUser();
        String body = "{\"email\":\"%s\"}".formatted(email);
        for (int i = 0; i < 3; i++) {
            postJson("/auth/forgot-password", body, newIp()).andExpect(status().isAccepted());
        }

        postJson("/auth/forgot-password", body, newIp()).andExpect(status().isTooManyRequests());
    }

    @Test
    void resendVerificationIsThrottledPerAddress() throws Exception {
        String body = "{\"email\":\"%s\"}".formatted(newEmail());
        for (int i = 0; i < 3; i++) {
            postJson("/auth/resend-verification", body, newIp()).andExpect(status().isAccepted());
        }

        postJson("/auth/resend-verification", body, newIp()).andExpect(status().isTooManyRequests());
    }

    @Test
    void refreshIsThrottledPerIpAddress() throws Exception {
        String ip = newIp();
        int allowed = 0;
        // 60 per minute refills gradually (about one per second), so allow a little slack.
        for (int i = 0; i < 80 && allowed == i; i++) {
            int status = postWithCookie("/auth/refresh", "never-issued", ip).andReturn().getResponse().getStatus();
            if (status == 401) {
                allowed++;
            } else {
                assertThat(status).isEqualTo(429);
            }
        }

        assertThat(allowed).isBetween(60, 79);
    }

    @Test
    void verifyAndResetAttemptsAreThrottledPerIpAddress() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 10; i++) {
            postJson("/auth/verify-email", "{\"token\":\"guess\"}", ip).andExpect(status().isBadRequest());
        }
        postJson("/auth/verify-email", "{\"token\":\"guess\"}", ip).andExpect(status().isTooManyRequests());

        String resetIp = newIp();
        String body = "{\"token\":\"guess\",\"newPassword\":\"a-long-enough-password\"}";
        for (int i = 0; i < 10; i++) {
            postJson("/auth/reset-password", body, resetIp).andExpect(status().isBadRequest());
        }
        postJson("/auth/reset-password", body, resetIp).andExpect(status().isTooManyRequests());
    }
}
