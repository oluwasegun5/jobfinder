package com.jobfinder.core.identity.internal;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Browser-facing hardening, under {@code app.security} (docs/adr/0037-security-hardening.md).
 *
 * <p>{@code cors.allowedOrigins} is an exact-match list. Blank means "just the web app's origin" (derived from
 * {@code app.auth.web-base-url}): the Next server proxies {@code /api/core} and forwards the browser's {@code Origin},
 * so that origin must always be known. A wildcard is refused at startup, and credentials are only ever allowed to the
 * listed origins.
 */
@ConfigurationProperties("app.security")
@Validated
record WebSecurityProperties(
        @Valid @DefaultValue Cors cors,
        @Valid @DefaultValue Csrf csrf,
        @Valid @DefaultValue Hsts hsts) {

    record Cors(
            @DefaultValue List<String> allowedOrigins,
            @DefaultValue("false") boolean allowCredentials,
            @DefaultValue("10m") @NotNull Duration maxAge) {
    }

    /**
     * {@code extensionOrigins}: exact {@code chrome-extension://<id>} origins whose requests may use the cookie
     * endpoints. Empty by default; this never adds an {@code Access-Control-Allow-Origin} header for them.
     */
    record Csrf(@DefaultValue List<String> extensionOrigins) {
    }

    /** {@code maxAge} of zero turns HSTS off; it is only ever sent on requests that arrived over TLS. */
    record Hsts(@DefaultValue("365d") @NotNull Duration maxAge, @DefaultValue("true") boolean includeSubDomains) {
    }

    /** {@code scheme://host[:port]}, lower-cased, no path; throws on anything else (including {@code *}). */
    static String normalizeOrigin(String value) {
        String text = value == null ? "" : value.trim();
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.security origin is not a valid origin: " + text);
        }
        boolean hasPath = uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/");
        if (uri.getScheme() == null || uri.getHost() == null && !"chrome-extension".equals(uri.getScheme())
                || hasPath || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("app.security origin must look like scheme://host[:port]: " + text);
        }
        String authority = uri.getRawAuthority() == null ? "" : uri.getRawAuthority();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + authority.toLowerCase(Locale.ROOT);
    }

    /** The CORS allow-list actually in force: the configured list, else the web app's own origin. */
    List<String> effectiveCorsOrigins(String webBaseUrl) {
        List<String> configured = cors.allowedOrigins().stream().filter(o -> !o.isBlank())
                .map(WebSecurityProperties::normalizeOrigin).distinct().toList();
        return configured.isEmpty() ? List.of(normalizeOrigin(webBaseUrl)) : configured;
    }

    List<String> extensionOrigins() {
        return csrf.extensionOrigins().stream().filter(o -> !o.isBlank())
                .map(WebSecurityProperties::normalizeOrigin).distinct().toList();
    }
}
