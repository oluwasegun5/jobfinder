package com.jobfinder.core.applications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/**
 * Status changes and the history they leave (docs/adr/0032-application-tracker.md): every change is an event, a change to
 * the status it already has is a no-op, a few moves are refused, and the history cannot be rewritten.
 */
class ApplicationStatusTests extends ApplicationsTestSupport {

    @Test
    void everyChangeIsRecordedInOrderWithItsFromAndToAndNote() throws Exception {
        Session me = newSession();
        String id = idOf(create(me, "{\"title\":\"Dev\",\"status\":\"SAVED\"}"));

        postAs(me, "/applications/" + id + "/status", "{\"status\":\"APPLIED\",\"note\":\"Sent via the portal\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        moveTo(me, id, "SCREENING").andExpect(status().isOk());
        postAs(me, "/applications/" + id + "/status", "{\"status\":\"INTERVIEW\",\"note\":\"Panel on Thursday\"}")
                .andExpect(status().isOk());

        String detail = body(getAs(me, "/applications/" + id));
        assertThat(JsonPath.<List<String>>read(detail, "$.events[*].to")).containsExactly("SAVED", "APPLIED",
                "SCREENING", "INTERVIEW");
        assertThat(JsonPath.<List<String>>read(detail, "$.events[1:].from")).containsExactly("SAVED", "APPLIED",
                "SCREENING");
        assertThat(JsonPath.<String>read(detail, "$.events[1].note")).isEqualTo("Sent via the portal");
        assertThat(JsonPath.<String>read(detail, "$.events[3].note")).isEqualTo("Panel on Thursday");
        List<String> times = JsonPath.read(detail, "$.events[*].at");
        assertThat(times).isSorted();
        assertThat(JsonPath.<String>read(detail, "$.statusChangedAt")).isEqualTo(times.get(3));
    }

    @Test
    void theSameStatusIsAnOkNoOpThatRecordsNothing() throws Exception {
        Session me = newSession();
        String id = manual(me, "Dev");
        String before = body(getAs(me, "/applications/" + id));

        postAs(me, "/applications/" + id + "/status", "{\"status\":\"APPLIED\",\"note\":\"ignored\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.events.length()").value(1));

        assertThat(body(getAs(me, "/applications/" + id))).isEqualTo(before);
    }

    @Test
    void movingOutOfSavedIntoAnAppliedStatusSetsAppliedAtOnce() throws Exception {
        Session me = newSession();
        String saved = idOf(create(me, "{\"title\":\"Dev\",\"status\":\"SAVED\"}"));
        String direct = idOf(create(me, "{\"title\":\"Dev 2\",\"status\":\"SAVED\"}"));

        moveTo(me, saved, "APPLIED").andExpect(status().isOk()).andExpect(jsonPath("$.appliedAt").isString());
        // straight to an interview still means the user applied
        moveTo(me, direct, "INTERVIEW").andExpect(status().isOk()).andExpect(jsonPath("$.appliedAt").isString());
        // later moves keep the date
        String applied = JsonPath.read(body(getAs(me, "/applications/" + saved)), "$.appliedAt");
        moveTo(me, saved, "OFFER").andExpect(status().isOk()).andExpect(jsonPath("$.appliedAt").value(applied));
    }

    @Test
    void withdrawingASavedApplicationDoesNotPretendItWasApplied() throws Exception {
        Session me = newSession();
        String id = idOf(create(me, "{\"title\":\"Dev\",\"status\":\"SAVED\"}"));

        moveTo(me, id, "WITHDRAWN").andExpect(status().isOk()).andExpect(jsonPath("$.appliedAt").doesNotExist());
    }

    @Test
    void movesBackwardsAndOutOfRejectedAreAllowed() throws Exception {
        Session me = newSession();
        String id = manual(me, "Dev");

        moveTo(me, id, "REJECTED").andExpect(status().isOk());
        moveTo(me, id, "INTERVIEW").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INTERVIEW"));
        moveTo(me, id, "APPLIED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(JsonPath.<List<?>>read(body(getAs(me, "/applications/" + id)), "$.events")).hasSize(4);
    }

    @Test
    void anApplicationThatHasLeftSavedCannotGoBackToIt() throws Exception {
        Session me = newSession();
        String id = manual(me, "Dev");

        moveTo(me, id, "SAVED").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("invalid_transition"));

        getAs(me, "/applications/" + id).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.events.length()").value(1));
    }

    @Test
    void anUnknownOrMissingStatusIsRefusedAndChangesNothing() throws Exception {
        Session me = newSession();
        String id = manual(me, "Dev");

        postAs(me, "/applications/" + id + "/status", "{\"status\":\"GHOSTED\"}").andExpect(status().isBadRequest());
        postAs(me, "/applications/" + id + "/status", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        postAs(me, "/applications/" + id + "/status", "{\"status\":\"OFFER\",\"note\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest());

        getAs(me, "/applications/" + id).andExpect(jsonPath("$.status").value("APPLIED"));
    }

    @Test
    void rejectingOrWithdrawingCancelsThePendingRemindersAndNothingElse() throws Exception {
        Session me = newSession();
        String one = manual(me, "One");
        String two = manual(me, "Two");
        String r1 = idOf(addReminder(me, one, Instant.now().plus(Duration.ofDays(2)), null));
        String r2 = idOf(addReminder(me, one, Instant.now().plus(Duration.ofDays(3)), null));
        String other = idOf(addReminder(me, two, Instant.now().plus(Duration.ofDays(2)), null));

        moveTo(me, one, "REJECTED").andExpect(status().isOk()).andExpect(jsonPath("$.nextReminderAt").doesNotExist())
                .andExpect(jsonPath("$.reminders[0].state").value("CANCELLED"))
                .andExpect(jsonPath("$.reminders[0].cancelReason").value("APPLICATION_CLOSED"));

        assertThat(reminderState(r1)).isEqualTo("CANCELLED");
        assertThat(reminderState(r2)).isEqualTo("CANCELLED");
        assertThat(reminderState(other)).isEqualTo("PENDING");
        // a closed application takes no new reminder
        addReminder(me, one, Instant.now().plus(Duration.ofDays(2)), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("application_closed"));
    }

    @Test
    void theHistoryCannotBeRewrittenByTheDatabaseEither() throws Exception {
        Session me = newSession();
        UUID userId = userIdOf(me);
        String id = manual(me, "Dev");

        assertThatThrownBy(() -> jdbc.update("update application_events set to_status = 'OFFER' where application_id = ?::uuid",
                id)).hasMessageContaining("append only");
        assertThatThrownBy(() -> jdbc.update("update application_events set note = 'x' where user_id = ?", userId))
                .hasMessageContaining("append only");
        // an event that says nothing changed is not an event
        assertThatThrownBy(() -> jdbc.update("""
                insert into application_events (id, application_id, user_id, from_status, to_status, at)
                values (gen_random_uuid(), ?::uuid, ?, 'APPLIED', 'APPLIED', now())
                """, id, userId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(count("select count(*) from application_events where application_id = ?::uuid and to_status = 'APPLIED'",
                id)).isEqualTo(1);
    }
}
