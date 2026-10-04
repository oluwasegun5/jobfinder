package com.jobfinder.core.identity.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Defence in depth for the one place a browser attaches a credential by itself: the refresh cookie
 * (ASVS V4.2.2, V13.2.3). {@code SameSite=Strict} is the primary control; this filter additionally refuses a
 * state-changing request to a cookie-authenticated endpoint when the browser says it was started by another site.
 * Everything else authenticates with a bearer header that a foreign page cannot attach, so it needs no such check, and
 * webhooks (signature-authenticated) are not covered.
 *
 * <p>The rule, in order, for a POST/PUT/PATCH/DELETE to {@code /auth/refresh}, {@code /auth/logout} or any request that
 * carries the refresh cookie:
 * <ol>
 * <li>{@code Origin} present: allowed only if it is one of the trusted web origins or a configured extension origin;
 * anything else (including {@code null}) is refused. {@code Origin} cannot be forged by page script.</li>
 * <li>No {@code Origin}, {@code Sec-Fetch-Site} present: allowed for {@code same-origin} and {@code none}, refused for
 * {@code same-site} and {@code cross-site}.</li>
 * <li>Neither header: allowed. A browser sends at least one of them on every cross-site POST, so this is a non-browser
 * client (curl, a server, a test), which cannot be used for CSRF. This is the documented fallback.</li>
 * </ol>
 */
final class CrossSiteRequestFilter extends OncePerRequestFilter {

    private static final Set<String> COOKIE_ENDPOINTS = Set.of("/auth/refresh", "/auth/logout");

    private final Set<String> trustedOrigins;
    private final String cookieName;

    CrossSiteRequestFilter(List<String> trustedOrigins, List<String> extensionOrigins, String refreshCookieName) {
        this.trustedOrigins = new java.util.HashSet<>(trustedOrigins);
        this.trustedOrigins.addAll(extensionOrigins);
        this.cookieName = refreshCookieName;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (isSafe(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !COOKIE_ENDPOINTS.contains(path) && !carriesRefreshCookie(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isCrossSite(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                    + "\"detail\":\"Cross-site requests are not allowed here.\","
                    + "\"code\":\"cross_site_request_blocked\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isCrossSite(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            return !trustedOrigins.contains(origin.trim().toLowerCase(Locale.ROOT));
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null) {
            String site = fetchSite.trim().toLowerCase(Locale.ROOT);
            return !(site.equals("same-origin") || site.equals("none"));
        }
        return false;
    }

    private boolean carriesRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafe(String method) {
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method) || HttpMethod.TRACE.matches(method);
    }
}
