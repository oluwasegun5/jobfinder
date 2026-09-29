package com.jobfinder.core.identity.internal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.identity.internal.AuthDtos.AuthResponse;
import com.jobfinder.core.identity.internal.AuthDtos.EmailRequest;
import com.jobfinder.core.identity.internal.AuthDtos.LoginRequest;
import com.jobfinder.core.identity.internal.AuthDtos.MeResponse;
import com.jobfinder.core.identity.internal.AuthDtos.ResetPasswordRequest;
import com.jobfinder.core.identity.internal.AuthDtos.SignupRequest;
import com.jobfinder.core.identity.internal.AuthDtos.TokenRequest;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Email/password authentication. The access token is returned in the body (the web app keeps
 * it in memory); the refresh token exists only as an httpOnly, SameSite=Strict cookie scoped
 * to the auth endpoints, so scripts can never read it.
 *
 * <p>Endpoints that accept an email answer 202 whether or not the account exists.
 * The client IP is the socket peer; when deployed behind a proxy, enable Spring's
 * forwarded-header handling for a trusted proxy so this reflects the real client.
 */
@RestController
@RequestMapping("/auth")
class AuthController {

    private final AuthService auth;
    private final AuthProperties properties;

    AuthController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        auth.signup(request.email(), request.password(), http.getRemoteAddr());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        auth.verifyEmail(request.token(), http.getRemoteAddr());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        auth.resendVerification(request.email(), http.getRemoteAddr());
    }

    @PostMapping("/login")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return respond(auth.login(request.email(), request.password(), http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    ResponseEntity<AuthResponse> refresh(HttpServletRequest http) {
        Cookie cookie = WebUtils.getCookie(http, properties.refreshCookie().name());
        try {
            return respond(auth.refresh(cookie != null ? cookie.getValue() : null, http.getRemoteAddr()));
        } catch (AuthException e) {
            if (e.code().equals("invalid_refresh_token")) {
                // Drop the dead cookie so the browser stops sending it.
                throw e.withHeader(HttpHeaders.SET_COOKIE, clearedCookie().toString());
            }
            throw e;
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest http) {
        Cookie cookie = WebUtils.getCookie(http, properties.refreshCookie().name());
        auth.logout(cookie != null ? cookie.getValue() : null);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedCookie().toString()).build();
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        auth.forgotPassword(request.email(), http.getRemoteAddr());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        auth.resetPassword(request.token(), request.newPassword(), http.getRemoteAddr());
    }

    @GetMapping("/me")
    MeResponse me() {
        User user = auth.currentUser(CurrentUser.require().id());
        return new MeResponse(user.getId(), user.getEmail(), user.getRole().name(), user.isEmailVerified());
    }

    private ResponseEntity<AuthResponse> respond(AuthService.Session session) {
        ResponseCookie cookie = baseCookie(session.refresh().value())
                .maxAge(session.refresh().ttl())
                .build();
        AuthResponse body = new AuthResponse(session.access().value(), "Bearer",
                session.access().ttl().toSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private ResponseCookie clearedCookie() {
        return baseCookie("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        AuthProperties.RefreshCookie config = properties.refreshCookie();
        return ResponseCookie.from(config.name(), value)
                .httpOnly(true)
                .secure(config.secure())
                .sameSite("Strict")
                .path(config.path());
    }
}
