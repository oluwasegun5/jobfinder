package com.jobfinder.core.identity.internal;

import java.util.List;
import java.util.Set;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Checks Google ID tokens against Google's published signing keys (fetched and cached by the
 * decoder). The web app obtains the token with Google Identity Services and posts it to
 * {@code /auth/google}; the {@code aud} claim must be our client ID so a token minted for another
 * app is rejected.
 */
@Component
class JwksGoogleTokenVerifier implements GoogleTokenVerifier {

    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;
    private volatile NimbusJwtDecoder decoder;

    JwksGoogleTokenVerifier(AuthProperties properties) {
        this.clientId = properties.google().clientId();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (clientId == null || clientId.isBlank()) {
            throw AuthException.googleNotConfigured();
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            throw AuthException.invalidGoogleToken();
        }
        String email = jwt.getClaimAsString("email");
        if (jwt.getSubject() == null || email == null) {
            throw AuthException.invalidGoogleToken();
        }
        return new GoogleIdentity(jwt.getSubject(), email, Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")));
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
                    OAuth2TokenValidator<Jwt> issuer = jwt -> ISSUERS.contains(jwt.getClaimAsString("iss"))
                                    ? OAuth2TokenValidatorResult.success()
                                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "bad issuer", null));
                    OAuth2TokenValidator<Jwt> audience = jwt -> {
                        List<String> aud = jwt.getAudience();
                        return aud != null && aud.contains(clientId)
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "bad audience", null));
                    };
                    built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                            new JwtTimestampValidator(), issuer, audience));
                    decoder = built;
                }
                local = decoder;
            }
        }
        return local;
    }
}
