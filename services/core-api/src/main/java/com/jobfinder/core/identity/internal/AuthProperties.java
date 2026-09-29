package com.jobfinder.core.identity.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Configuration under {@code app.auth}. Startup fails if the JWT secret is missing or too short. */
@ConfigurationProperties("app.auth")
@Validated
record AuthProperties(
        @Valid @NotNull Jwt jwt,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue("24h") Duration verificationTokenTtl,
        @DefaultValue("1h") Duration resetTokenTtl,
        @DefaultValue("12") @Min(4) @Max(31) int bcryptStrength,
        @DefaultValue("http://localhost:3000") @NotBlank String webBaseUrl,
        @DefaultValue("no-reply@jobfinder.local") @NotBlank String mailFrom,
        @Valid @DefaultValue RefreshCookie refreshCookie) {

    record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (set JWT_SECRET)") String secret,
            @DefaultValue("jobfinder-core-api") @NotBlank String issuer,
            @DefaultValue("15m") Duration accessTokenTtl) {
    }

    /**
     * {@code path} is what the browser sees: the web app proxies core-api under
     * {@code /api/core} (docs/adr/0011), so the cookie is scoped to {@code /api/core/auth}.
     */
    record RefreshCookie(
            @DefaultValue("refresh_token") @NotBlank String name,
            @DefaultValue("/api/core/auth") @NotBlank String path,
            @DefaultValue("true") boolean secure) {
    }
}
