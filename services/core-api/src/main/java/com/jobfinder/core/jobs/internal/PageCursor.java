package com.jobfinder.core.jobs.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.jobfinder.core.shared.ApiException;

import tools.jackson.databind.json.JsonMapper;

/**
 * The opaque cursor of a keyset page: where the last row of the previous page sits in the sort order
 * ({@code key}, then {@code id} as the tie-breaker), which listing it belongs to ({@code mode}), a digest of the
 * query it was issued for ({@code query}) and the instant the first page was computed ({@code asOf}, so ranking
 * that depends on "now" does not drift between pages).
 *
 * <p>Base64url of a small JSON document. It is not signed: it only ever selects a position in the caller's own
 * results (every value is bound as a query parameter), so a forged cursor can at worst skip or repeat the caller's
 * own rows. A malformed one, or one used with other parameters, is a 400.
 */
record PageCursor(int v, String mode, String key, UUID id, long asOf, String query) {

    static final int VERSION = 1;

    static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String encode(JsonMapper json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(this));
    }

    Instant asOfInstant() {
        return Instant.ofEpochMilli(asOf);
    }

    /** Reads {@code token}, which must have been issued for this {@code mode} and {@code query}. */
    static PageCursor decode(JsonMapper json, String token, String mode, String query) {
        PageCursor cursor;
        try {
            cursor = json.readValue(Base64.getUrlDecoder().decode(token), PageCursor.class);
        } catch (RuntimeException e) {
            throw invalid();
        }
        if (cursor == null || cursor.v != VERSION || cursor.mode == null || cursor.key == null || cursor.id == null
                || !mode.equals(cursor.mode) || !query.equals(cursor.query) || !validKey(mode, cursor.key)) {
            throw invalid();
        }
        return cursor;
    }

    /** The key must parse the way the query that reads it will parse it (a forged key is a 400, never a 500). */
    private static boolean validKey(String mode, String key) {
        try {
            if ("K".equals(mode)) {
                if (key.startsWith("A:")) {
                    return Double.isFinite(Double.parseDouble(key.substring(2)));
                }
                if (key.startsWith("B:")) {
                    OffsetDateTime.parse(key.substring(2));
                    return true;
                }
                return false;
            }
            if ("S".equals(mode)) {
                return Double.isFinite(Double.parseDouble(key));
            }
            OffsetDateTime.parse(key);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor",
                "The cursor is not valid for this request; start again from the first page.");
    }
}
