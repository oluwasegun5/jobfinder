package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.notifications.internal.NotificationDtos.DigestFrequency;
import com.jobfinder.core.notifications.internal.Windows.Window;

/** When a digest is due: the user's own hour and weekday, in their zone, and not hours late. */
class WindowsTests {

    private static final Duration LATE = Duration.ofHours(6);
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos"); // UTC+1, no DST
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    private static java.util.Optional<Window> daily(String now, ZoneId zone, int hour) {
        return Windows.due(Instant.parse(now), zone, hour, 1, DigestFrequency.DAILY, LATE);
    }

    private static java.util.Optional<Window> weekly(String now, ZoneId zone, int hour, int weekday) {
        return Windows.due(Instant.parse(now), zone, hour, weekday, DigestFrequency.WEEKLY, LATE);
    }

    @Test
    void aDailyDigestIsDueFromItsHourForTheLatenessAllowed() {
        assertThat(daily("2026-10-01T08:05:00Z", UTC, 8)).get().satisfies(w -> {
            assertThat(w.key()).isEqualTo("D:2026-10-01");
            assertThat(w.scheduled()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        });
        assertThat(daily("2026-10-01T14:00:00Z", UTC, 8)).isPresent();
        assertThat(daily("2026-10-01T14:00:01Z", UTC, 8)).isEmpty();
    }

    @Test
    void beforeTheHourTheLastWindowIsYesterdaysAndTooOld() {
        assertThat(daily("2026-10-01T07:59:00Z", UTC, 8)).isEmpty();
        // late evening hour: yesterday's 22:00 is still within reach just after midnight
        assertThat(daily("2026-10-01T01:00:00Z", UTC, 22)).get().extracting(Window::key).isEqualTo("D:2026-09-30");
    }

    @Test
    void theHourIsTheUsersOwn() {
        // 07:05 UTC is 08:05 in Lagos, but only 00:05 in Los Angeles
        assertThat(daily("2026-10-01T07:05:00Z", LAGOS, 8)).isPresent();
        assertThat(daily("2026-10-01T07:05:00Z", LA, 8)).isEmpty();
        assertThat(daily("2026-10-01T15:05:00Z", LA, 8)).get().extracting(Window::key).isEqualTo("D:2026-10-01");
    }

    @Test
    void theWindowDateIsTheLocalDate() {
        // 23:30 UTC on 30 Sep is already 1 Oct in Lagos (00:30), and the 00:00 moment is that day's
        assertThat(daily("2026-09-30T23:30:00Z", LAGOS, 0)).get().extracting(Window::key).isEqualTo("D:2026-10-01");
        assertThat(daily("2026-09-30T23:30:00Z", UTC, 0)).isEmpty(); // that day's midnight is 23h30 ago
    }

    @Test
    void aWeeklyDigestIsDueOnTheUsersWeekdayOnly() {
        // 2026-10-01 is a Thursday (4)
        assertThat(weekly("2026-10-01T08:05:00Z", UTC, 8, 4)).get().extracting(Window::key).isEqualTo("W:2026-10-01");
        assertThat(weekly("2026-10-02T08:05:00Z", UTC, 8, 4)).isEmpty();
        assertThat(weekly("2026-10-01T08:05:00Z", UTC, 8, 5)).isEmpty();
        // Monday digest, run on a Monday morning: the window is named after that Monday
        assertThat(weekly("2026-09-28T08:05:00Z", UTC, 8, 1)).get().extracting(Window::key).isEqualTo("W:2026-09-28");
    }

    @Test
    void aWeeklyWindowSpansTheWeekendAcrossAMonthBoundary() {
        // Friday 2026-10-02 evening digest, looked at an hour after: the window is that Friday
        assertThat(weekly("2026-10-02T18:30:00Z", UTC, 18, 5)).get().extracting(Window::key).isEqualTo("W:2026-10-02");
        // Sunday digest at 23:00 crossing into Monday UTC: still a Sunday window for 2 hours
        assertThat(weekly("2026-10-05T01:00:00Z", UTC, 23, 7)).get().extracting(Window::key).isEqualTo("W:2026-10-04");
    }

    @Test
    void clockChangesDoNotMoveTheLocalHour() {
        // US clocks went back on 2026-11-01: 08:05 local is 16:05 UTC before and 16:05... no, 15:05 -> 16:05 after
        assertThat(daily("2026-10-31T15:05:00Z", LA, 8)).get().extracting(Window::key).isEqualTo("D:2026-10-31"); // PDT
        assertThat(daily("2026-11-01T16:05:00Z", LA, 8)).get().extracting(Window::key).isEqualTo("D:2026-11-01"); // PST
        assertThat(daily("2026-11-01T15:05:00Z", LA, 8)).isEmpty(); // 07:05 PST: not yet
        // a spring-forward gap: 02:30 does not exist on 2026-03-08 in Los Angeles; the moment moves to 03:00 PDT
        assertThat(Windows.due(Instant.parse("2026-03-08T10:05:00Z"), LA, 2, 1, DigestFrequency.DAILY, LATE))
                .get().extracting(Window::key).isEqualTo("D:2026-03-08");
    }
}
