package com.jobfinder.core.identity;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Entry point for other modules to learn who is calling. Every endpoint that touches user
 * data must scope its queries by {@code CurrentUser.require().id()}, never by an ID taken
 * from the request.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @throws IllegalStateException if called outside an authenticated request */
    public static AuthenticatedUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("No authenticated user in the current request");
        }
        Jwt jwt = token.getToken();
        return new AuthenticatedUser(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }
}
