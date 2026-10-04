package com.jobfinder.core.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The per-IP login limit is keyed on the visitor, not on the web proxy that every visitor arrives through (ASVS 11.1.4).
 * Before the fix all visitors shared one budget, so twenty failed logins from one address locked everybody out.
 */
class ClientIpRateLimitTests extends AuthTestSupport {

    private static final AtomicInteger NEXT = new AtomicInteger((int) (System.nanoTime() % 20_000) + 1_000);

    private static String publicIp() {
        int n = NEXT.incrementAndGet();
        return "100.%d.%d.%d".formatted(n / 65536 % 256 + 64, n / 256 % 256, n % 256);
    }

    private ResultActions loginVia(String proxy, String forwarded) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(newEmail(), PASSWORD)).header("X-Forwarded-For", forwarded)
                .with(request -> {
                    request.setRemoteAddr(proxy);
                    return request;
                }));
    }

    @Test
    void visitorsArrivingThroughTheSameProxyHaveTheirOwnLoginBudget() throws Exception {
        String proxy = newIp();
        String loud = publicIp();
        String quiet = publicIp();
        for (int i = 0; i < 20; i++) {
            loginVia(proxy, loud).andExpect(status().isUnauthorized());
        }
        loginVia(proxy, loud).andExpect(status().isTooManyRequests());

        loginVia(proxy, quiet).andExpect(status().isUnauthorized());
    }

    @Test
    void aVisitorCannotEscapeTheLimitByInventingForwardedAddresses() throws Exception {
        String proxy = newIp();
        String real = publicIp();
        for (int i = 0; i < 20; i++) {
            loginVia(proxy, publicIp() + ", " + real).andExpect(status().isUnauthorized());
        }

        loginVia(proxy, publicIp() + ", " + real).andExpect(status().isTooManyRequests());
    }

    @Test
    void aDirectClientCannotChooseItsOwnAddress() throws Exception {
        String direct = publicIp(); // not a trusted proxy
        for (int i = 0; i < 20; i++) {
            loginVia(direct, publicIp()).andExpect(status().isUnauthorized());
        }

        loginVia(direct, publicIp()).andExpect(status().isTooManyRequests());
    }
}
