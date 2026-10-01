package com.jobfinder.core.feed.internal;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.jobfinder.core.shared.ApiException;

import tools.jackson.databind.json.JsonMapper;

/**
 * The opaque cursor of a feed page: the position of the last item of the previous page in the feed's order, and
 * {@code asOf}, the instant the first page was computed. Feedback is read as of that instant for every later page, so
 * a save or hide made while the user pages on takes effect on the next first page and never shuffles the pages they
 * are walking through (no job repeats or is skipped because another one moved).
 *
 * <p>The order is: model-scored before estimated ({@code tier} 0, 1), then {@code feedMillis} (the feed score in
 * thousandths) descending, then {@code stage2Millis} descending, then {@code id}. Base64url JSON, not signed: it only
 * selects a position in the caller's own feed. A malformed one is a 400.
 */
record FeedCursor(int v, long asOf, int tier, long feedMillis, long stage2Millis, UUID id) {

    static final int VERSION = 1;

    String encode(JsonMapper json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(this));
    }

    Instant asOfInstant() {
        return Instant.ofEpochMilli(asOf);
    }

    static FeedCursor decode(JsonMapper json, String token) {
        FeedCursor cursor;
        try {
            cursor = json.readValue(Base64.getUrlDecoder().decode(token), FeedCursor.class);
        } catch (RuntimeException e) {
            throw invalid();
        }
        if (cursor == null || cursor.v != VERSION || cursor.id == null || cursor.tier < 0 || cursor.tier > 1) {
            throw invalid();
        }
        return cursor;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor",
                "The cursor is not valid for this request; start again from the first page.");
    }
}
