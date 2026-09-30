package com.jobfinder.core.identity.internal;

import org.springframework.http.HttpStatus;

import com.jobfinder.core.shared.ApiException;

/** Authentication and account-flow failures. Messages are deliberately generic to avoid leaking account state. */
class AuthException extends ApiException {

    private AuthException(HttpStatus status, String code, String detail) {
        super(status, code, detail);
    }

    static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password.");
    }

    static AuthException emailNotVerified() {
        return new AuthException(HttpStatus.FORBIDDEN, "email_not_verified",
                "Verify your email address before logging in.");
    }

    static AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
                "Refresh token is invalid, expired or revoked.");
    }

    static AuthException invalidToken() {
        return new AuthException(HttpStatus.BAD_REQUEST, "invalid_token", "Token is invalid or has expired.");
    }

    static AuthException invalidGoogleToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "invalid_google_token",
                "Google sign-in could not be verified.");
    }

    static AuthException googleNotConfigured() {
        return new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "google_not_configured",
                "Google sign-in is not available.");
    }

    static AuthException weakPassword(String detail) {
        return new AuthException(HttpStatus.BAD_REQUEST, "weak_password", detail);
    }

    static AuthException rateLimited(long retryAfterSeconds) {
        AuthException ex = new AuthException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many requests. Try again later.");
        ex.withHeader("Retry-After", Long.toString(retryAfterSeconds));
        return ex;
    }

    static AuthException rateLimiterUnavailable() {
        return new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "rate_limiter_unavailable",
                "Service temporarily unavailable. Try again shortly.");
    }
}
