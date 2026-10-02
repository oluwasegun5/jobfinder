package com.jobfinder.core.applications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.jobfinder.core.notifications.MailDeliveryException;
import com.jobfinder.core.notifications.RequestedMail;
import com.jobfinder.core.notifications.UserMail;

/**
 * When the mail server refuses (docs/adr/0032-application-tracker.md): the reminder is tried again after the retry delay,
 * at most {@code max-attempts} times in all, then cancelled as {@code SEND_FAILED}; one failing reminder does not stop
 * the others. The mail server is replaced by one that fails for the user under test and accepts everybody else.
 */
class ReminderFailureTests extends ApplicationsTestSupport {

    @MockitoBean
    private UserMail mail;

    @Autowired
    private ReminderService sender;

    private final AtomicInteger sends = new AtomicInteger();

    private void failFor(UUID userId, int times) {
        org.mockito.Mockito.when(mail.send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    UUID to = call.getArgument(0, UUID.class);
                    if (!to.equals(userId)) {
                        return UserMail.Result.SENT;
                    }
                    if (sends.incrementAndGet() <= times) {
                        throw new MailDeliveryException(new IOException("refused"));
                    }
                    return UserMail.Result.SENT;
                });
    }

    private void claimGoneStale(String reminder) {
        jdbc.update("update reminders set claimed_at = now() - interval '1 hour' where id = ?::uuid", reminder);
    }

    private String dueReminder(Session me) throws Exception {
        String reminder = idOf(addReminder(me, manual(me, "Dev"), Instant.now().plus(Duration.ofDays(2)), null));
        makeDue(reminder);
        return reminder;
    }

    @Test
    void aFailedSendIsTriedAgainAfterTheRetryDelayAndThenSent() throws Exception {
        Session me = newSession();
        String reminder = dueReminder(me);
        failFor(userIdOf(me), 1);

        assertThat(sender.sendDue(Instant.now())).isZero();
        assertThat(reminderState(reminder)).isEqualTo("PENDING");
        // not again at once: the retry delay has not passed
        assertThat(sender.sendDue(Instant.now())).isZero();
        assertThat(sends.get()).isEqualTo(1);

        claimGoneStale(reminder);
        assertThat(sender.sendDue(Instant.now())).isEqualTo(1);

        assertThat(reminderState(reminder)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select attempts from reminders where id = ?::uuid", Integer.class, reminder))
                .isEqualTo(2);
    }

    @Test
    void aReminderThatKeepsFailingIsGivenUpOnAfterTheMaximumAttempts() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        String reminder = idOf(addReminder(me, app, Instant.now().plus(Duration.ofDays(2)), null));
        makeDue(reminder);
        failFor(userIdOf(me), Integer.MAX_VALUE);

        for (int attempt = 0; attempt < 5; attempt++) {
            claimGoneStale(reminder);
            sender.sendDue(Instant.now());
        }

        assertThat(sends.get()).isEqualTo(3);
        assertThat(reminderState(reminder)).isEqualTo("CANCELLED");
        getAs(me, "/applications/" + app + "/reminders").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cancelReason").value("SEND_FAILED"));
    }

    @Test
    void oneFailingReminderDoesNotStopTheOthers() throws Exception {
        Session failing = newSession();
        Session fine = newSession();
        String bad = dueReminder(failing);
        String good = dueReminder(fine);
        failFor(userIdOf(failing), Integer.MAX_VALUE);

        sender.sendDue(Instant.now());

        assertThat(reminderState(bad)).isEqualTo("PENDING");
        assertThat(reminderState(good)).isEqualTo("SENT");
        jdbc.update("delete from reminders where id = ?::uuid", bad);
    }

    @Test
    void theMailIsComposedFromTheApplicationAndNeverFromTheRequest() throws Exception {
        Session me = newSession();
        String reminder = dueReminder(me);
        failFor(userIdOf(me), 0);

        sender.sendDue(Instant.now());

        org.mockito.ArgumentCaptor<RequestedMail> sent = org.mockito.ArgumentCaptor.forClass(RequestedMail.class);
        org.mockito.Mockito.verify(mail, org.mockito.Mockito.atLeastOnce()).send(
                org.mockito.ArgumentMatchers.eq(userIdOf(me)), sent.capture());
        assertThat(sent.getValue().subject()).isEqualTo("Reminder: follow up on Dev at Acme");
        assertThat(reminderState(reminder)).isEqualTo("SENT");
    }
}
