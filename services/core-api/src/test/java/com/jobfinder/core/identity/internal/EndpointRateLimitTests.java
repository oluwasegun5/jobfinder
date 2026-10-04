package com.jobfinder.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jobfinder.core.EndpointInventory;
import com.jobfinder.core.EndpointInventory.Endpoint;
import com.jobfinder.core.identity.AuthTestSupport;
import com.jobfinder.core.shared.ApiException;

/**
 * Work item 4: every endpoint has a rate-limit class, each class has a limit, the limit is keyed per user (per IP when
 * nobody is signed in) and a refusal is an RFC 7807 429 with Retry-After. Small limits are set for three classes here;
 * every other suite runs against the relaxed test ceilings in application.properties, never against weakened defaults.
 */
@TestPropertySource(properties = {
        "app.rate-limit.endpoints.AI.capacity=3", "app.rate-limit.endpoints.AI.period=1h",
        "app.rate-limit.endpoints.SEARCH.capacity=2", "app.rate-limit.endpoints.SEARCH.period=1h",
        "app.rate-limit.endpoints.PUBLIC_LINK.capacity=2", "app.rate-limit.endpoints.PUBLIC_LINK.period=1h" })
class EndpointRateLimitTests extends AuthTestSupport {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void aiEndpointsAreLimitedPerUserAndTheRefusalIsAProblemWithRetryAfter() throws Exception {
        String alice = accessToken();
        String bob = accessToken();

        for (int i = 0; i < 3; i++) {
            matchFor(alice).andExpect(notRateLimited());
        }
        MockHttpServletResponse blocked = matchFor(alice).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"))
                .andExpect(jsonPath("$.status").value(429))
                .andReturn().getResponse();
        assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isPositive();
        assertThat(blocked.getContentType()).startsWith("application/problem+json");

        // Another user has their own bucket, and Alice's other classes are untouched.
        matchFor(bob).andExpect(notRateLimited());
        getMe(alice).andExpect(status().isOk());
    }

    @Test
    void theLimitIsPerClassSoSearchCannotUseUpTheAiBudget() throws Exception {
        String token = accessToken();
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/jobs").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
        mvc.perform(get("/jobs").header("Authorization", "Bearer " + token)).andExpect(status().isTooManyRequests());

        matchFor(token).andExpect(notRateLimited());
    }

    @Test
    void anUnauthenticatedCallerIsRefusedBeforeItSpendsAnything() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(get("/jobs/{id}/match", UUID.randomUUID())).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void signedLinkEndpointsAreLimitedPerClientIp() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 2; i++) {
            unsubscribe(ip).andExpect(status().is4xxClientError()).andExpect(r -> assertThat(r.getResponse().getStatus())
                    .isNotEqualTo(429));
        }
        unsubscribe(ip).andExpect(status().isTooManyRequests());
        unsubscribe(newIp()).andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(429));
    }

    @Test
    void everyExplicitRuleNamesARealEndpointAndEveryEndpointHasAClass() throws Exception {
        List<Endpoint> endpoints = EndpointInventory.all(handlerMapping);
        List<String> keys = endpoints.stream().map(Endpoint::key).toList();

        List<String> stale = EndpointClassifier.RULES.stream()
                .map(rule -> rule.method().name() + " " + rule.pattern()).filter(key -> !keys.contains(key)).toList();
        assertThat(stale).as("rate-limit rules for endpoints that do not exist").isEmpty();

        Map<EndpointClass, List<String>> byClass = endpoints.stream().collect(Collectors.groupingBy(
                e -> EndpointClassifier.classify(e.method(), e.pattern()),
                () -> new java.util.TreeMap<>(), Collectors.mapping(Endpoint::key, Collectors.toList())));
        assertThat(endpoints).isNotEmpty();
        assertThat(byClass.values().stream().mapToInt(List::size).sum()).isEqualTo(endpoints.size());

        // The inventory the security review quotes, generated from the mappings.
        StringBuilder table = new StringBuilder("| Class | Endpoint |\n|---|---|\n");
        byClass.forEach((c, list) -> list.forEach(key -> table.append("| ").append(c).append(" | `").append(key)
                .append("` |\n")));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "rate-limit-inventory.md"), table.toString());
    }

    @Test
    void theCatchAllClassesFailOpenAndTheExpensiveOnesFailClosedWhenRedisIsDown() throws Exception {
        RateLimitProperties dead = new RateLimitProperties("redis://127.0.0.1:1", Map.of(), Map.of());
        RateLimiter limiter = new RateLimiter(dead);
        EndpointRateLimitInterceptor interceptor = new EndpointRateLimitInterceptor(limiter, dead,
                new ClientIpResolver(new WebSecurityProperties(
                        new WebSecurityProperties.Cors(java.util.List.of(), false, java.time.Duration.ofMinutes(10)),
                        new WebSecurityProperties.Csrf(java.util.List.of()),
                        new WebSecurityProperties.Hsts(java.time.Duration.ofDays(365), true), java.util.List.of())));
        HandlerMethod handler = new HandlerMethod(this, Object.class.getMethod("hashCode"));

        assertThat(interceptor.preHandle(request("GET", "/saved-jobs"), new MockHttpServletResponse(), handler)).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request("POST", "/jobs/{id}/tailor"),
                new MockHttpServletResponse(), handler)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("rate_limiter_unavailable"));
        limiter.destroy();
    }

    /** The handler ran (here it answers 409: the new user has no CV), so the limiter let the request through. */
    private static org.springframework.test.web.servlet.ResultMatcher notRateLimited() {
        return result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429).isNotEqualTo(401);
    }

    private static MockHttpServletRequest request(String method, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, pattern);
        request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setRemoteAddr("10.250.0.1");
        return request;
    }

    private ResultActions matchFor(String token) throws Exception {
        return mvc.perform(get("/jobs/{id}/match", UUID.randomUUID()).header("Authorization", "Bearer " + token));
    }

    private ResultActions unsubscribe(String ip) throws Exception {
        return mvc.perform(get("/notifications/unsubscribe/not-a-real-token").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }));
    }

    private String accessToken() throws Exception {
        return login(registerVerifiedUser(), PASSWORD, newIp()).accessToken();
    }
}
