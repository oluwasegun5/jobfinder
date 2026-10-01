package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Deleting an account removes its notification settings, saved searches and send log, and only theirs. */
class NotificationDeletionTests extends NotificationsTestSupport {

    @Test
    void deletingTheAccountPurgesItsNotificationData() throws Exception {
        Session me = newSession();
        UUID id = userIdOf(me);
        Session other = newSession();
        UUID otherId = userIdOf(other);
        for (UUID u : new UUID[] { id, otherId }) {
            settings(u, true, "DAILY", 8, 1, null, true, 85);
            postJsonAs(u == id ? me : other, "/saved-searches",
                    "{\"name\":\"Go\",\"criteria\":{\"q\":\"golang\"},\"frequency\":\"DAILY\"}").andExpect(status().isCreated());
            jdbc.update("insert into notification_log (id, user_id, kind, scope, window_key, status, attempts, "
                    + "created_at, updated_at) values (?, ?, 'FOR_YOU_DIGEST', 'for-you', ?, 'SENT', 1, ?, ?)",
                    UUID.randomUUID(), u, "D:2026-10-01", java.sql.Timestamp.from(Instant.now()),
                    java.sql.Timestamp.from(Instant.now()));
        }

        mvc.perform(delete("/me").header("Authorization", bearer(me))).andExpect(status().isNoContent());

        for (String table : new String[] { "notification_preferences", "saved_searches", "notification_log" }) {
            assertThat(count("select count(*) from " + table + " where user_id = ?", id)).as(table).isZero();
            assertThat(count("select count(*) from " + table + " where user_id = ?", otherId)).as(table + " other")
                    .isEqualTo(1);
        }
    }
}
