package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;

/**
 * Response headers (work item 1), CORS (2) and the cross-site guard on the cookie endpoints (3). The web origin is
 * {@code http://localhost:3000} (the default {@code app.auth.web-base-url}); a second origin is allowed explicitly and
 * one extension origin may use the cookie endpoints.
 */
@TestPropertySource(properties = {
        "app.security.cors.allowed-origins=http://localhost:3000,https://app.example.test",
        "app.security.csrf.extension-origins=chrome-extension://abcdefghijklmnopabcdefghijklmnop" })
class SecurityHeadersAndCorsTests extends AuthTestSupport {

    private static final String WEB = "http://localhost:3000";
    private static final String EXTENSION = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";

    // ---- headers -------------------------------------------------------------------------------------------------

    @Test
    void authenticatedJsonCarriesTheFullHeaderSetAndIsNotCacheable() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());

        MockHttpServletResponse response = getMe(session.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Permissions-Policy")).contains("camera=()", "geolocation=()", "microphone=()");
        assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'",
                "default-src 'none'");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-site");
    }

    @Test
    void errorResponsesFromTheFilterChainCarryTheHeadersToo() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void hstsIsSentOverTlsOnlyAndNeverOverPlainHttp() throws Exception {
        mvc.perform(get("/actuator/health").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
        mvc.perform(get("/actuator/health")).andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void theDocumentationUiIsNotLockedOutByTheApiPolicy() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(header().doesNotExist("Content-Security-Policy"));
    }

    // ---- CORS ----------------------------------------------------------------------------------------------------

    @Test
    void anAllowListedOriginGetsExactEchoAndVaryOrigin() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/actuator/health").header("Origin", "https://app.example.test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.test"))
                .andReturn().getResponse();

        assertThat(response.getHeaders("Vary")).anyMatch(v -> v.contains("Origin"));
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");
    }

    @Test
    void aDisallowedOriginIsRefusedAndGetsNoCorsHeaders() throws Exception {
        mvc.perform(get("/actuator/health").header("Origin", "https://evil.example.test"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void theNullOriginIsRefused() throws Exception {
        mvc.perform(get("/actuator/health").header("Origin", "null"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightIsLimitedToTheMethodsAndHeadersTheClientUses() throws Exception {
        MockHttpServletResponse ok = preflight(WEB, "PUT", "authorization,content-type").andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo(WEB);
        assertThat(ok.getHeader("Access-Control-Allow-Methods")).contains("PUT").doesNotContain("TRACE", "OPTIONS");
        assertThat(ok.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("authorization");

        preflight(WEB, "TRACE", "authorization").andExpect(status().isForbidden());
        preflight(WEB, "POST", "x-custom-header").andExpect(status().isForbidden());
        preflight("https://evil.example.test", "POST", "authorization").andExpect(status().isForbidden());
    }

    @Test
    void theExtensionOriginGetsNoCorsHeadersButIsNotRefusedByTheCorsFilter() throws Exception {
        // Its service worker has host permissions, so it needs no ACAO; the CORS filter must still let it through.
        mvc.perform(get("/actuator/health").header("Origin", EXTENSION))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // ---- cross-site guard on the cookie endpoints ---------------------------------------------------------------

    @Test
    void sameOriginRefreshAndLogoutAreAccepted() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());

        MockHttpServletResponse refreshed = cookiePost("/auth/refresh", session.refreshToken(), request -> request
                .header("Origin", WEB).header("Sec-Fetch-Site", "same-origin")).andExpect(status().isOk())
                .andReturn().getResponse();
        Session next = sessionFrom(refreshed);

        cookiePost("/auth/logout", next.refreshToken(), request -> request.header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNoContent());
    }

    @Test
    void aCrossSiteRefreshIsRejectedBeforeTheCookieIsLookedAt() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());

        // A foreign Origin is already refused by the CORS filter; the guard is what catches the rest.
        cookiePost("/auth/refresh", session.refreshToken(), request -> request
                .header("Origin", "https://evil.example.test").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        cookiePost("/auth/refresh", session.refreshToken(), request -> request.header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("cross_site_request_blocked"));
        cookiePost("/auth/refresh", session.refreshToken(), request -> request.header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isForbidden());
        cookiePost("/auth/logout", session.refreshToken(), request -> request.header("Origin", "null"))
                .andExpect(status().isForbidden());

        // The rejected attempts did not consume or revoke anything: the session still refreshes.
        cookiePost("/auth/refresh", session.refreshToken(), request -> request.header("Origin", WEB))
                .andExpect(status().isOk());
    }

    @Test
    void requestsWithNeitherHeaderAreAcceptedAsNonBrowserClients() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());

        cookiePost("/auth/refresh", session.refreshToken(), request -> { }).andExpect(status().isOk());
    }

    @Test
    void aConfiguredExtensionOriginMayUseTheCookieEndpointsButAnotherMayNot() throws Exception {
        String email = registerVerifiedUser();
        Session session = login(email, PASSWORD, newIp());

        cookiePost("/auth/refresh", session.refreshToken(), request -> request.header("Origin",
                "chrome-extension://zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")).andExpect(status().isForbidden());
        cookiePost("/auth/refresh", session.refreshToken(), request -> request.header("Origin", EXTENSION)
                .header("Sec-Fetch-Site", "cross-site")).andExpect(status().isOk());
    }

    @Test
    void theGuardOnlyCoversCookieEndpointsSoBearerEndpointsAreUntouched() throws Exception {
        // A bearer-authenticated POST from a foreign site has no ambient credential to abuse: it is the CORS filter's job
        // (disallowed origin: 403 above), not the cross-site guard's. Login is a credential POST with no cookie.
        postJson("/auth/login", credentials(newEmail(), PASSWORD), newIp()).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType("application/json").content(credentials(newEmail(), PASSWORD))
                .header("Sec-Fetch-Site", "cross-site")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions preflight(String origin, String method, String headers)
            throws Exception {
        return mvc.perform(options("/jobs").header("Origin", origin).header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers));
    }

    private org.springframework.test.web.servlet.ResultActions cookiePost(String path, String refreshToken,
            java.util.function.Consumer<MockHttpServletRequestBuilder> customizer) throws Exception {
        MockHttpServletRequestBuilder request = post(path).cookie(new Cookie(COOKIE_NAME, refreshToken))
                .with(r -> {
                    r.setRemoteAddr(newIp());
                    return r;
                });
        customizer.accept(request);
        return mvc.perform(request);
    }
}
