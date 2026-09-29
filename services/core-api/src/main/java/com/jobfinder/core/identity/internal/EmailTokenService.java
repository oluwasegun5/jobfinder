package com.jobfinder.core.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Single-use emailed tokens: address verification and password reset. */
@Service
class EmailTokenService {

    private final EmailTokenRepository tokens;
    private final Clock clock;

    EmailTokenService(EmailTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Issues a fresh token and voids any earlier unused token of the same type for the user. */
    @Transactional
    String issue(UUID userId, EmailTokenType type, Duration ttl) {
        Instant now = clock.instant();
        tokens.invalidateOutstanding(userId, type, now);
        String raw = Tokens.generate();
        tokens.save(new EmailToken(userId, type, Tokens.hash(raw), now.plus(ttl)));
        return raw;
    }

    /** Consumes the token if it is live, returning the user it was issued to. */
    @Transactional
    Optional<UUID> consume(String raw, EmailTokenType type) {
        String hash = Tokens.hash(raw);
        Optional<EmailToken> token = tokens.findByTokenHashAndType(hash, type);
        if (token.isEmpty() || tokens.consume(hash, type, clock.instant()) == 0) {
            return Optional.empty();
        }
        return token.map(EmailToken::getUserId);
    }
}
