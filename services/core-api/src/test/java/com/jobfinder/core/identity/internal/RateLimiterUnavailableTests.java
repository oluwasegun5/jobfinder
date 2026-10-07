package com.jobfinder.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.jobfinder.core.shared.ApiException;

class RateLimiterUnavailableTests {

    @Test
    void failsClosedWhenRedisCannotBeReached() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties("redis://127.0.0.1:1", Map.of(), Map.of()));

        assertThatThrownBy(() -> limiter.check(RateLimitRule.LOGIN_IP, "10.9.9.9"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("rate_limiter_unavailable");
                });
        limiter.destroy();
    }
}
