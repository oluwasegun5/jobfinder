package com.jobfinder.core.applications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting an account takes the user's applications, status history and reminders with it, and nobody else's
 * (docs/adr/0032-application-tracker.md).
 */
class ApplicationsDeletionTests extends ApplicationsTestSupport {

    @Autowired
    private ReminderService sender;

    private String applicationWithHistoryAndReminders(Session session) throws Exception {
        String app = manual(session, "Dev");
        moveTo(session, app, "SCREENING").andExpect(status().isOk());
        moveTo(session, app, "INTERVIEW").andExpect(status().isOk());
        addReminder(session, app, Instant.now().plus(Duration.ofDays(2)), null).andExpect(status().isCreated());
        addReminder(session, app, Instant.now().plus(Duration.ofDays(4)), "\"kind\":\"INTERVIEW\"")
                .andExpect(status().isCreated());
        return app;
    }

    @Test
    void deletingTheAccountRemovesItsApplicationsEventsAndReminders() throws Exception {
        Session me = newSession();
        UUID userId = userIdOf(me);
        applicationWithHistoryAndReminders(me);
        manual(me, "Second");
        assertThat(rows("applications", userId)).isEqualTo(2);
        assertThat(rows("application_events", userId)).isEqualTo(4);
        assertThat(rows("reminders", userId)).isEqualTo(2);

        mvc.perform(delete("/me").header("Authorization", bearer(me))).andExpect(status().isNoContent());

        assertThat(rows("applications", userId)).isZero();
        assertThat(rows("application_events", userId)).isZero();
        assertThat(rows("reminders", userId)).isZero();
    }

    @Test
    void anotherUsersTrackerIsUntouched() throws Exception {
        Session me = newSession();
        Session other = newSession();
        UUID otherId = userIdOf(other);
        String othersApp = applicationWithHistoryAndReminders(other);
        applicationWithHistoryAndReminders(me);

        mvc.perform(delete("/me").header("Authorization", bearer(me))).andExpect(status().isNoContent());

        assertThat(rows("applications", otherId)).isEqualTo(1);
        assertThat(rows("application_events", otherId)).isEqualTo(3);
        assertThat(rows("reminders", otherId)).isEqualTo(2);
        getAs(other, "/applications/" + othersApp).andExpect(status().isOk());
    }

    @Test
    void aPendingReminderOfADeletedAccountIsNeverSent() throws Exception {
        Session me = newSession();
        UUID userId = userIdOf(me);
        String address = emailOf(userId);
        String app = manual(me, "Dev");
        String reminder = idOf(addReminder(me, app, Instant.now().plus(Duration.ofDays(2)), null));
        makeDue(reminder);

        mvc.perform(delete("/me").header("Authorization", bearer(me))).andExpect(status().isNoContent());
        sender.sendDue(Instant.now());

        assertThat(mails(address).stream().filter(m -> m.subject().startsWith("Reminder"))).isEmpty();
    }
}
