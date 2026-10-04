package com.jobfinder.core.shared;

import java.time.Duration;

import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * The response headers every core-api filter chain sends (ASVS V14.4, V14.5). The API only ever returns JSON, problem
 * documents, redirects-free downloads and (outside production) the Swagger UI, so the policy is the strictest one that
 * still lets those work:
 *
 * <ul>
 * <li>{@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY} and CSP {@code frame-ancestors 'none'};</li>
 * <li>{@code Referrer-Policy: no-referrer} and a {@code Permissions-Policy} that turns every powerful feature off;</li>
 * <li>{@code Cache-Control: no-store} (Spring Security's cache-control writer, which leaves a header the handler already
 * set alone), so no authenticated response is cached by a browser or proxy;</li>
 * <li>HSTS, written only on requests that arrived over TLS (directly, or through a trusted proxy once forwarded-header
 * handling is on), so plain-http local development never pins itself to https.</li>
 * </ul>
 *
 * The API CSP is {@code default-src 'none'}: a JSON response renders nothing. The Swagger UI paths are exempt because the
 * UI needs scripts and styles; the docs are switched off in the {@code prod} profile.
 */
public final class SecurityHeaderDefaults {

    /** The browser features a JSON API never needs. */
    static final String PERMISSIONS_POLICY = "accelerometer=(), autoplay=(), camera=(), display-capture=(), "
            + "geolocation=(), gyroscope=(), microphone=(), midi=(), payment=(), usb=(), interest-cohort=()";

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private SecurityHeaderDefaults() {
    }

    /** Applies the policy to one chain; {@code hstsMaxAge} of zero leaves HSTS off. */
    public static void apply(HttpSecurity http, Duration hstsMaxAge, boolean hstsIncludeSubDomains) throws Exception {
        http.headers(headers -> {
            headers.contentTypeOptions(options -> { });
            headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
            headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER));
            headers.permissionsPolicyHeader(policy -> policy.policy(PERMISSIONS_POLICY));
            headers.cacheControl(cache -> { });
            if (hstsMaxAge.isZero()) {
                headers.httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable);
            } else {
                headers.httpStrictTransportSecurity(hsts -> hsts
                        .maxAgeInSeconds(hstsMaxAge.toSeconds()).includeSubDomains(hstsIncludeSubDomains));
            }
            // A page-less API: CSP everywhere except the documentation UI.
            headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                    new NegatedRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**")),
                    new StaticHeadersWriter("Content-Security-Policy", API_CSP)));
            // Cross-origin isolation: other origins cannot embed or read our responses through no-cors loads.
            headers.crossOriginResourcePolicy(corp -> corp.policy(
                    org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter
                            .CrossOriginResourcePolicy.SAME_SITE));
        });
    }
}
