package com.jobfinder.core.identity.internal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.identity.CurrentUser;

/** Self-service account operations on the authenticated user. */
@RestController
class AccountController {

    private final AuthService auth;
    private final AuthProperties properties;

    AccountController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    /** Permanently deletes the caller's account and everything stored about them. */
    @DeleteMapping("/me")
    ResponseEntity<Void> deleteMe() {
        auth.deleteAccount(CurrentUser.require().id());
        AuthProperties.RefreshCookie config = properties.refreshCookie();
        ResponseCookie cleared = ResponseCookie.from(config.name(), "")
                .httpOnly(true).secure(config.secure()).sameSite("Strict").path(config.path()).maxAge(0).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }
}
