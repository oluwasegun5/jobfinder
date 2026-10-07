package com.jobfinder.core.identity.internal;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.jobfinder.core.identity.AiConsent;

/** Reads and changes the AI-processing consent recorded on the user row (V35). */
@Service
class AiConsentService implements AiConsent {

    /** Version of the consent text the web shows. Bump it whenever the wording changes. */
    static final String CURRENT_VERSION = "2026-10";

    record Status(boolean granted, String version, java.time.Instant grantedAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    AiConsentService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean isGranted(UUID userId) {
        return jdbc.sql("select ai_consent_at is not null from users where id = :id").param("id", userId)
                .query(Boolean.class).optional().orElse(false);
    }

    Status status(UUID userId) {
        return jdbc.sql("select ai_consent_version, ai_consent_at from users where id = :id").param("id", userId)
                .query((rs, n) -> {
                    var at = rs.getObject("ai_consent_at", java.time.OffsetDateTime.class);
                    return new Status(at != null, rs.getString("ai_consent_version"),
                            at == null ? null : at.toInstant());
                }).optional().orElse(new Status(false, null, null));
    }

    Status grant(UUID userId) {
        jdbc.sql("update users set ai_consent_version = :v, ai_consent_at = :at, updated_at = :at where id = :id")
                .param("v", CURRENT_VERSION).param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .param("id", userId).update();
        return status(userId);
    }

    Status withdraw(UUID userId) {
        jdbc.sql("update users set ai_consent_version = null, ai_consent_at = null, updated_at = :at where id = :id")
                .param("at", clock.instant().atOffset(ZoneOffset.UTC)).param("id", userId).update();
        return status(userId);
    }
}
