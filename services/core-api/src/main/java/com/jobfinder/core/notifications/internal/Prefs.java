package com.jobfinder.core.notifications.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.jobfinder.core.notifications.internal.NotificationDtos.DigestFrequency;

/**
 * A user's notification settings as the senders read them. {@link #defaults} is what a user who never saved any has:
 * email on, digests and instant alerts off (they are opt-in).
 */
record Prefs(UUID userId, boolean emailEnabled, boolean digestEnabled, DigestFrequency digestFrequency, int digestHour,
        int digestWeekday, String timezone, boolean instantEnabled, int instantThreshold,
        Instant digestsUnsubscribedAt, Instant marketingUnsubscribedAt) {

    static final int DEFAULT_HOUR = 8;
    static final int DEFAULT_WEEKDAY = 1;
    static final int DEFAULT_THRESHOLD = 85;

    static Prefs defaults(UUID userId) {
        return new Prefs(userId, true, false, DigestFrequency.DAILY, DEFAULT_HOUR, DEFAULT_WEEKDAY, null, false,
                DEFAULT_THRESHOLD, null, null);
    }

    /** The user's time zone, or UTC when they did not give one. */
    ZoneId zone() {
        return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
    }

    /** May any digest (the "For you" one, a saved search's) be sent? */
    boolean digestsAllowed() {
        return emailEnabled && digestsUnsubscribedAt == null && marketingUnsubscribedAt == null;
    }

    /** May an instant alert be sent? */
    boolean alertsAllowed() {
        return emailEnabled && marketingUnsubscribedAt == null;
    }
}
