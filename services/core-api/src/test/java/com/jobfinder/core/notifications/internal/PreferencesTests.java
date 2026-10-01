package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** GET and PUT /notifications/preferences: defaults, round trip, validation, isolation. */
class PreferencesTests extends NotificationsTestSupport {

    private static String body(boolean digest, String frequency, int hour, int weekday, String tz, boolean instant,
            int threshold) {
        return """
                {"emailEnabled":true,"digestEnabled":%s,"digestFrequency":"%s","digestHour":%d,"digestWeekday":%d,%s
                 "instantEnabled":%s,"instantThreshold":%d}""".formatted(digest, frequency, hour, weekday,
                tz == null ? "" : "\"timezone\":\"" + tz + "\",", instant, threshold);
    }

    @Test
    void aUserWhoNeverSavedAnythingGetsTheDefaultsWithEverythingOptionalOff() throws Exception {
        Session me = newSession();

        getAs(me, "/notifications/preferences").andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.digestEnabled").value(false))
                .andExpect(jsonPath("$.instantEnabled").value(false))
                .andExpect(jsonPath("$.digestFrequency").value("DAILY"))
                .andExpect(jsonPath("$.digestHour").value(8))
                .andExpect(jsonPath("$.instantThreshold").value(85))
                .andExpect(jsonPath("$.digestsUnsubscribed").value(false))
                .andExpect(jsonPath("$.allUnsubscribed").value(false));
        // reading the defaults stores nothing
        assertThat(count("select count(*) from notification_preferences where user_id = ?", userIdOf(me))).isZero();
    }

    @Test
    void settingsRoundTripAndBelongToTheirUserOnly() throws Exception {
        Session me = newSession();
        Session other = newSession();

        putJsonAs(me, "/notifications/preferences", body(true, "WEEKLY", 18, 5, "Africa/Lagos", true, 90))
                .andExpect(status().isOk()).andExpect(jsonPath("$.digestEnabled").value(true))
                .andExpect(jsonPath("$.digestFrequency").value("WEEKLY"))
                .andExpect(jsonPath("$.digestHour").value(18)).andExpect(jsonPath("$.digestWeekday").value(5))
                .andExpect(jsonPath("$.timezone").value("Africa/Lagos"))
                .andExpect(jsonPath("$.instantThreshold").value(90));
        getAs(me, "/notifications/preferences").andExpect(jsonPath("$.timezone").value("Africa/Lagos"))
                .andExpect(jsonPath("$.instantEnabled").value(true));
        getAs(other, "/notifications/preferences").andExpect(jsonPath("$.digestEnabled").value(false))
                .andExpect(jsonPath("$.timezone").doesNotExist());

        // an absent timezone means UTC and saving again replaces, not merges
        putJsonAs(me, "/notifications/preferences", body(false, "DAILY", 7, 1, null, false, 60))
                .andExpect(status().isOk()).andExpect(jsonPath("$.timezone").doesNotExist());
        assertThat(count("select count(*) from notification_preferences where user_id = ?", userIdOf(me))).isEqualTo(1);
    }

    @Test
    void invalidSettingsAreRejectedAndChangeNothing() throws Exception {
        Session me = newSession();
        putJsonAs(me, "/notifications/preferences", body(true, "DAILY", 9, 1, "Europe/Paris", false, 85))
                .andExpect(status().isOk());

        String[] bad = { body(true, "DAILY", 24, 1, null, false, 85), body(true, "DAILY", -1, 1, null, false, 85),
                body(true, "DAILY", 8, 0, null, false, 85), body(true, "DAILY", 8, 8, null, false, 85),
                body(true, "DAILY", 8, 1, null, true, 49), body(true, "DAILY", 8, 1, null, true, 101),
                body(true, "HOURLY", 8, 1, null, false, 85), "{}" };
        for (String json : bad) {
            putJsonAs(me, "/notifications/preferences", json).andExpect(status().isBadRequest());
        }
        for (String zone : new String[] { "Mars/Base", "+01:00", "EST", "utc", "Europe/../etc", "x".repeat(65) }) {
            putJsonAs(me, "/notifications/preferences", body(true, "DAILY", 8, 1, zone, false, 85))
                    .andExpect(status().isBadRequest());
        }
        putJsonAs(me, "/notifications/preferences", body(true, "DAILY", 8, 1, "Mars/Base", false, 85))
                .andExpect(jsonPath("$.code").value("invalid_timezone"));
        getAs(me, "/notifications/preferences").andExpect(jsonPath("$.digestHour").value(9))
                .andExpect(jsonPath("$.timezone").value("Europe/Paris"));
        putJsonAs(me, "/notifications/preferences", body(true, "DAILY", 8, 1, "UTC", false, 85))
                .andExpect(status().isOk());
    }

    @Test
    void turningDigestsOrAlertsBackOnClearsTheEarlierUnsubscribe() throws Exception {
        Session me = newSession();
        UUID id = userIdOf(me);
        putJsonAs(me, "/notifications/preferences", body(false, "DAILY", 8, 1, null, false, 85))
                .andExpect(status().isOk());
        jdbc.update("update notification_preferences set digests_unsubscribed_at = now(), "
                + "marketing_unsubscribed_at = now() where user_id = ?", id);
        getAs(me, "/notifications/preferences").andExpect(jsonPath("$.digestsUnsubscribed").value(true))
                .andExpect(jsonPath("$.allUnsubscribed").value(true));

        // saving with both still off leaves the unsubscribe in place
        putJsonAs(me, "/notifications/preferences", body(false, "DAILY", 8, 1, null, false, 85))
                .andExpect(jsonPath("$.allUnsubscribed").value(true));

        putJsonAs(me, "/notifications/preferences", body(true, "DAILY", 8, 1, null, false, 85))
                .andExpect(jsonPath("$.digestsUnsubscribed").value(false))
                .andExpect(jsonPath("$.allUnsubscribed").value(false));
    }
}
