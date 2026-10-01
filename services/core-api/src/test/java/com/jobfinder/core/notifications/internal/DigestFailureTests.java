package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.jobfinder.core.notifications.internal.DigestService.Summary;

/**
 * A mail server that refuses: the failure is recorded (a class name, never an address), retried a bounded number of times
 * with a growing wait while the window is open, and never stops the run for the other users.
 */
class DigestFailureTests extends NotificationsTestSupport {

    @MockitoSpyBean
    private NotificationMailer mailer;

    private Account due(Instant now) {
        Account me = onboarded();
        UUID job = strongJob("Java Engineer " + me.id(), "Globex");
        score(me.id(), scores(job, 90));
        dailyDigestAt(me.id(), now);
        return me;
    }

    @Test
    void aFailedEmailIsRecordedWithoutPersonalDataAndRetriedAfterTheWait() {
        Instant now = Instant.now();
        Account me = due(now);
        doThrow(new MailSendException("Invalid Addresses: " + me.email())).doCallRealMethod().when(mailer)
                .send(eq(me.email()), any());

        Summary first = digests.runDue(now);

        assertThat(messages(me.email())).isEmpty();
        assertThat(first.failed()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForMap("select status, attempts, last_error from notification_log where user_id = ?",
                me.id())).containsEntry("status", "FAILED").containsEntry("attempts", 1)
                .containsEntry("last_error", "MailSendException");
        assertThat(jdbc.queryForObject("select last_error from notification_log where user_id = ?", String.class,
                me.id())).doesNotContain(me.email());

        digests.runDue(now.plus(Duration.ofMinutes(5))); // before the 15 minute wait is over: nothing is tried
        assertThat(messages(me.email())).isEmpty();
        assertThat(count("select attempts from notification_log where user_id = ?", me.id())).isEqualTo(1);

        digests.runDue(now.plus(Duration.ofMinutes(20)));
        assertThat(messages(me.email())).hasSize(1);
        assertThat(jdbc.queryForMap("select status, attempts from notification_log where user_id = ?", me.id()))
                .containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    @Test
    void anEmailThatKeepsFailingIsTriedAtMostThreeTimes() {
        Instant now = Instant.now();
        Account me = due(now);
        doThrow(new MailSendException("down")).when(mailer).send(eq(me.email()), any());

        digests.runDue(now); // attempt 1, next after 15 minutes
        digests.runDue(now.plus(Duration.ofMinutes(16))); // attempt 2, next after 30 minutes
        digests.runDue(now.plus(Duration.ofMinutes(47))); // attempt 3: the last
        digests.runDue(now.plus(Duration.ofHours(3)));
        digests.runDue(now.plus(Duration.ofHours(5)));

        verify(mailer, times(3)).send(eq(me.email()), any());
        assertThat(jdbc.queryForMap("select status, attempts from notification_log where user_id = ?", me.id()))
                .containsEntry("status", "FAILED").containsEntry("attempts", 3);
        assertThat(messages(me.email())).isEmpty();
    }

    @Test
    void oneUsersFailureNeverStopsTheOthers() {
        Instant now = Instant.now();
        Account broken = due(now);
        Account fine = due(now);
        doThrow(new MailSendException("refused")).when(mailer).send(eq(broken.email()), any());

        Summary summary = digests.runDue(now);

        assertThat(messages(fine.email())).hasSize(1);
        assertThat(messages(broken.email())).isEmpty();
        assertThat(summary.failed()).isGreaterThanOrEqualTo(1);
        assertThat(summary.sent()).isGreaterThanOrEqualTo(1);
    }
}
