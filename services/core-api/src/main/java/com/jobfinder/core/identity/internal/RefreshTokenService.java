package com.jobfinder.core.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token rotation with reuse detection. Every token is single-use: refreshing
 * revokes the presented token and issues a successor in the same family. Presenting a
 * token that was already revoked means it was stolen (or replayed), so the whole family
 * is revoked and both the attacker and the legitimate holder must log in again.
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    record Issued(String value, Duration ttl) {
    }

    record Rotated(User user, Issued token) {
    }

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final AuthProperties properties;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository tokens, UserRepository users, AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new family (a fresh login). */
    @Transactional
    Issued startFamily(UUID userId) {
        return issue(userId, UUID.randomUUID());
    }

    /**
     * The revocation of a reused family must survive the exception thrown to the caller,
     * hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = AuthException.class)
    Rotated rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken presented = tokens.findByTokenHash(Tokens.hash(rawToken))
                .orElseThrow(AuthException::invalidRefreshToken);

        if (presented.getRevokedAt() != null) {
            return reuseDetected(presented, now);
        }
        if (!presented.getExpiresAt().isAfter(now)) {
            throw AuthException.invalidRefreshToken();
        }
        // Atomic: of two concurrent presentations of the same token exactly one wins.
        if (tokens.revokeIfActive(presented.getId(), now) == 0) {
            return reuseDetected(presented, now);
        }

        User user = users.findById(presented.getUserId()).orElse(null);
        if (user == null || !user.canAuthenticate() || !user.isEmailVerified()) {
            tokens.revokeFamily(presented.getFamilyId(), now);
            throw AuthException.invalidRefreshToken();
        }
        return new Rotated(user, issue(user.getId(), presented.getFamilyId()));
    }

    /** Idempotent: unknown or already-revoked tokens are ignored. */
    @Transactional
    void revokeFamilyOf(String rawToken) {
        Optional<RefreshToken> token = tokens.findByTokenHash(Tokens.hash(rawToken));
        token.ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    @Transactional
    void revokeAllForUser(UUID userId) {
        tokens.revokeAllForUser(userId, clock.instant());
    }

    private Rotated reuseDetected(RefreshToken presented, Instant now) {
        tokens.revokeFamily(presented.getFamilyId(), now);
        log.warn("Refresh token reuse detected; revoked token family {} for user {}",
                presented.getFamilyId(), presented.getUserId());
        throw AuthException.invalidRefreshToken();
    }

    private Issued issue(UUID userId, UUID familyId) {
        String raw = Tokens.generate();
        Duration ttl = properties.refreshTokenTtl();
        tokens.save(new RefreshToken(userId, Tokens.hash(raw), familyId, clock.instant().plus(ttl)));
        return new Issued(raw, ttl);
    }
}
