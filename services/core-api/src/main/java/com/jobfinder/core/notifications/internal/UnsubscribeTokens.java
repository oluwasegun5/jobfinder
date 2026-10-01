package com.jobfinder.core.notifications.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.jobfinder.core.notifications.internal.NotificationDtos.UnsubscribeScope;

/**
 * Signed unsubscribe tokens (docs/adr/0028-notifications.md). A token says "this user, this scope, this saved search
 * (for the search scope), valid until this time" and carries an HMAC-SHA256 of that, so the link in an email works with
 * no login, nothing about it is stored, and it cannot be forged or changed to another user or scope. It is not secret
 * from its owner and does not need to be single use: unsubscribing is idempotent and always safe.
 *
 * <p>Format: {@code base64url(payload) "." base64url(mac)}, payload {@code 1|userId|SCOPE|searchId-or-dash|expiryEpochSeconds}.
 * The key is {@code app.notifications.unsubscribe-secret}, or when that is blank an HMAC of the JWT secret under a fixed
 * label, so the two uses of the one secret cannot be confused.
 */
@Component
class UnsubscribeTokens {

    /** What a verified token says. */
    record Claims(UUID userId, UnsubscribeScope scope, UUID savedSearchId, Instant expires) {
    }

    private static final String VERSION = "1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final NotificationProperties properties;
    private final Clock clock;

    UnsubscribeTokens(NotificationProperties properties, Clock clock,
            @Value("${app.auth.jwt.secret:}") String jwtSecret) {
        this.properties = properties;
        this.clock = clock;
        if (!properties.unsubscribeSecret().isBlank()) {
            if (properties.unsubscribeSecret().length() < 32) {
                throw new IllegalStateException("app.notifications.unsubscribe-secret must be at least 32 characters");
            }
            this.key = properties.unsubscribeSecret().getBytes(StandardCharsets.UTF_8);
        } else if (!jwtSecret.isBlank()) {
            this.key = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8),
                    "jobfinder-unsubscribe-token-key-v1".getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalStateException(
                    "Set NOTIFICATIONS_UNSUBSCRIBE_SECRET (or JWT_SECRET) so unsubscribe links can be signed");
        }
    }

    String issue(UUID userId, UnsubscribeScope scope, UUID savedSearchId) {
        long expiry = Instant.ofEpochMilli(clock.millis()).plus(properties.unsubscribeTokenTtl()).getEpochSecond();
        String payload = String.join("|", VERSION, userId.toString(), scope.name(),
                savedSearchId == null ? "-" : savedSearchId.toString(), Long.toString(expiry));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(hmac(key, bytes));
    }

    /** The claims of a genuine, unexpired token; empty for anything else (the reason is not revealed). */
    Optional<Claims> verify(String token) {
        if (token == null || token.length() > 400) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 1 || dot == token.length() - 1 || token.indexOf('.', dot + 1) >= 0) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(token.substring(0, dot));
            byte[] mac = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(mac, hmac(key, payload))) {
                return Optional.empty();
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 5 || !VERSION.equals(parts[0])) {
                return Optional.empty();
            }
            Instant expires = Instant.ofEpochSecond(Long.parseLong(parts[4]));
            if (!expires.isAfter(Instant.ofEpochMilli(clock.millis()))) {
                return Optional.empty();
            }
            UUID search = "-".equals(parts[3]) ? null : UUID.fromString(parts[3]);
            UnsubscribeScope scope = UnsubscribeScope.valueOf(parts[2]);
            if (scope == UnsubscribeScope.SAVED_SEARCH && search == null) {
                return Optional.empty();
            }
            return Optional.of(new Claims(UUID.fromString(parts[1]), scope, search, expires));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
