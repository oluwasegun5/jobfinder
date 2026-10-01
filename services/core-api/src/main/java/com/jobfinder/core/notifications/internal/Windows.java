package com.jobfinder.core.notifications.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import com.jobfinder.core.notifications.internal.NotificationDtos.DigestFrequency;

/**
 * When a digest is due (docs/adr/0028-notifications.md). A digest has a scheduled moment in the user's own time zone:
 * their hour of the day (daily), or their hour on their weekday (weekly). The run finds the most recent such moment at or
 * before now; the digest is due if that moment is not older than {@code maxLateness}, and the moment's local date names
 * the window ({@code D:2026-10-01}, {@code W:2026-09-28}) the log claims it under. A run that comes too late does not
 * send a "morning" digest in the evening: the window just passes.
 */
final class Windows {

    /** The window a digest belongs to and the moment it was scheduled for. */
    record Window(String key, Instant scheduled) {
    }

    private Windows() {
    }

    static Optional<Window> due(Instant now, ZoneId zone, int hour, int weekday, DigestFrequency frequency,
            Duration maxLateness) {
        boolean daily = frequency == DigestFrequency.DAILY;
        LocalDate date = now.atZone(zone).toLocalDate();
        if (!daily) {
            while (date.getDayOfWeek().getValue() != weekday) {
                date = date.minusDays(1);
            }
        }
        ZonedDateTime scheduled = date.atTime(hour, 0).atZone(zone);
        if (scheduled.toInstant().isAfter(now)) { // today's moment is still ahead: the last one was a day or week ago
            date = date.minusDays(daily ? 1 : 7);
            scheduled = date.atTime(hour, 0).atZone(zone);
        }
        if (Duration.between(scheduled.toInstant(), now).compareTo(maxLateness) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Window((daily ? "D:" : "W:") + date, scheduled.toInstant()));
    }
}
