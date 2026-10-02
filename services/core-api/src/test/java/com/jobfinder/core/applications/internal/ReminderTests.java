package com.jobfinder.core.applications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jayway.jsonpath.JsonPath;

/**
 * Reminders (docs/adr/0032-application-tracker.md): setting, listing and cancelling them, and the sender: a due reminder
 * sends an email through the platform's mail server (Mailpit here), exactly once even with several senders at the same
 * moment, and never to someone who switched email notifications off.
 */
class ReminderTests extends ApplicationsTestSupport {

    @Autowired
    private ReminderService sender;

    private static Instant in(int days) {
        return Instant.now().plus(Duration.ofDays(days));
    }

    /** Only the reminders: signing up sent the user a verification email too. */
    private List<Mail> reminderMails(String to) throws Exception {
        return mails(to).stream().filter(m -> m.subject().startsWith("Reminder")).toList();
    }

    private List<Mail> awaitReminders(String to, int count) {
        return org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150))
                .until(() -> reminderMails(to), found -> found.size() >= count);
    }

    // --- the user's side ---

    @Test
    void aReminderIsAddedPendingWithItsKindAndNote() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");

        addReminder(me, app, in(3), "\"kind\":\"INTERVIEW\",\"note\":\"Prepare the system design round\"")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.applicationId").value(app)).andExpect(jsonPath("$.kind").value("INTERVIEW"))
                .andExpect(jsonPath("$.state").value("PENDING")).andExpect(jsonPath("$.note").value("Prepare the system design round"))
                .andExpect(jsonPath("$.sentAt").doesNotExist());
        addReminder(me, app, in(5), null).andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("FOLLOW_UP"));
    }

    @Test
    void nothingIsAddedBehindTheUsersBackWhenTheyApply() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        moveTo(me, app, "SCREENING").andExpect(status().isOk());

        getAs(me, "/applications/" + app + "/reminders").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void theDueTimeMustBeInTheFutureAndWithinAYear() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");

        addReminder(me, app, Instant.now().minusSeconds(5), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("reminder_in_past"));
        addReminder(me, app, in(400), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("reminder_too_far"));
        addReminder(me, app, in(1), "\"kind\":\"SHOUT\"").andExpect(status().isBadRequest());
        addReminder(me, app, in(1), "\"note\":\"" + "x".repeat(501) + "\"").andExpect(status().isBadRequest());
        postAs(me, "/applications/" + app + "/reminders", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        assertThat(rows("reminders", userIdOf(me))).isZero();
    }

    @Test
    void anApplicationHoldsAtMostTwentyPendingReminders() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        for (int i = 0; i < 20; i++) {
            addReminder(me, app, in(1).plusSeconds(i), null).andExpect(status().isCreated());
        }

        addReminder(me, app, in(2), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("too_many_reminders"));
        // a cancelled one frees a place
        String first = JsonPath.read(body(getAs(me, "/applications/" + app + "/reminders")), "$.items[0].id");
        deleteAs(me, "/applications/" + app + "/reminders/" + first).andExpect(status().isNoContent());
        addReminder(me, app, in(2), null).andExpect(status().isCreated());
    }

    @Test
    void remindersAreListedByDueTimeWithTheirState() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        String late = idOf(addReminder(me, app, in(9), null));
        String soon = idOf(addReminder(me, app, in(2), null));
        String cancelled = idOf(addReminder(me, app, in(5), null));
        deleteAs(me, "/applications/" + app + "/reminders/" + cancelled).andExpect(status().isNoContent());

        String list = body(getAs(me, "/applications/" + app + "/reminders"));

        assertThat(JsonPath.<List<String>>read(list, "$.items[*].id")).containsExactly(soon, cancelled, late);
        assertThat(JsonPath.<List<String>>read(list, "$.items[*].state")).containsExactly("PENDING", "CANCELLED",
                "PENDING");
        assertThat(JsonPath.<String>read(list, "$.items[1].cancelReason")).isEqualTo("USER");
        // the board shows the earliest pending one
        getAs(me, "/applications/" + app).andExpect(jsonPath("$.nextReminderAt").value(
                JsonPath.<String>read(list, "$.items[0].dueAt")));
    }

    @Test
    void cancellingIsIdempotentAndASentReminderCannotBeTakenBack() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        String cancelled = idOf(addReminder(me, app, in(2), null));
        String sent = idOf(addReminder(me, app, in(2), null));

        deleteAs(me, "/applications/" + app + "/reminders/" + cancelled).andExpect(status().isNoContent());
        deleteAs(me, "/applications/" + app + "/reminders/" + cancelled).andExpect(status().isNoContent());
        makeDue(sent);
        assertThat(sender.sendDue(Instant.now())).isEqualTo(1);
        deleteAs(me, "/applications/" + app + "/reminders/" + sent).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reminder_already_sent"));
        deleteAs(me, "/applications/" + app + "/reminders/" + UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("reminder_not_found"));
    }

    // --- the sender: the Done-when of P4.5 ---

    @Test
    void aDueReminderSendsAnEmailAndIsMarkedSent() throws Exception {
        Session me = newSession();
        UUID userId = userIdOf(me);
        String app = idOf(create(me, "{\"title\":\"Platform <b>Engineer</b>\",\"company\":\"Globex\"}"));
        String reminder = idOf(addReminder(me, app, in(2),
                "\"note\":\"Ask about <script>alert(1)</script> the team\""));
        makeDue(reminder);

        assertThat(sender.sendDue(Instant.now())).isEqualTo(1);

        Mail mail = awaitReminders(emailOf(userId), 1).get(0);
        assertThat(mail.subject()).isEqualTo("Reminder: follow up on Platform <b>Engineer</b> at Globex");
        assertThat(mail.text()).contains("Platform <b>Engineer</b> at Globex").contains("Ask about <script>alert(1)</script> the team")
                .contains("Status: APPLIED").contains("http://localhost:3000/applications")
                .contains("http://localhost:3000/settings/notifications");
        // everything that came from the user is escaped in the HTML part
        assertThat(mail.html()).contains("Platform &lt;b&gt;Engineer&lt;/b&gt; at Globex")
                .contains("Ask about &lt;script&gt;alert(1)&lt;/script&gt; the team").doesNotContain("<script>")
                .doesNotContain("<b>Engineer</b>");
        getAs(me, "/applications/" + app + "/reminders").andExpect(jsonPath("$.items[0].state").value("SENT"))
                .andExpect(jsonPath("$.items[0].sentAt").isString());
        getAs(me, "/applications/" + app).andExpect(jsonPath("$.nextReminderAt").doesNotExist());
    }

    @Test
    void aReminderIsSentOnceWhateverHowManyTimesTheSenderRuns() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        makeDue(idOf(addReminder(me, app, in(2), null)));

        assertThat(sender.sendDue(Instant.now())).isEqualTo(1);
        assertThat(sender.sendDue(Instant.now())).isZero();
        assertThat(sender.sendDue(Instant.now().plus(Duration.ofDays(1)))).isZero();

        assertThat(reminderMails(emailOf(userIdOf(me)))).hasSize(1);
    }

    @Test
    void severalSendersAtTheSameMomentSendEachReminderExactlyOnce() throws Exception {
        Session me = newSession();
        String address = emailOf(userIdOf(me));
        int reminders = 12;
        for (int i = 0; i < reminders; i++) {
            makeDue(idOf(addReminder(me, manual(me, "Dev " + i), in(2), null)));
        }

        // four "instances", released together, each running the sender (batches of 100, so one claims what it can)
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return sender.sendDue(Instant.now());
                }));
            }
            go.countDown();
            int sent = 0;
            for (Future<Integer> run : runs) {
                sent += run.get();
            }
            assertThat(sent).isEqualTo(reminders);
        } finally {
            pool.shutdownNow();
        }

        List<Mail> mails = awaitReminders(address, reminders);
        assertThat(mails).hasSize(reminders);
        assertThat(mails.stream().map(Mail::subject).distinct()).hasSize(reminders);
        assertThat(count("select count(*) from reminders where user_id = ? and state = 'SENT' and attempts = 1",
                userIdOf(me))).isEqualTo(reminders);
    }

    @Test
    void aReminderThatIsNotDueYetIsLeftAlone() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        String reminder = idOf(addReminder(me, app, in(2), null));

        assertThat(sender.sendDue(Instant.now())).isZero();
        assertThat(reminderState(reminder)).isEqualTo("PENDING");
        assertThat(reminderMails(emailOf(userIdOf(me)))).isEmpty();
        // the same reminder is sent once its time has come
        makeDue(reminder);
        sender.sendDue(Instant.now());
        assertThat(reminderState(reminder)).isEqualTo("SENT");
    }

    @Test
    void aCancelledReminderIsNeverSent() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        String reminder = idOf(addReminder(me, app, in(2), null));
        deleteAs(me, "/applications/" + app + "/reminders/" + reminder).andExpect(status().isNoContent());
        makeDue(reminder);

        assertThat(sender.sendDue(Instant.now())).isZero();
        assertThat(reminderMails(emailOf(userIdOf(me)))).isEmpty();
    }

    @Test
    void aUserWhoSwitchedEmailNotificationsOffGetsNoReminderAndItIsCancelledWithTheReason() throws Exception {
        Session me = newSession();
        UUID userId = userIdOf(me);
        jdbc.update("insert into notification_preferences (user_id, email_enabled, created_at, updated_at) "
                + "values (?, false, now(), now())", userId);
        String app = manual(me, "Dev");
        String reminder = idOf(addReminder(me, app, in(2), null));
        makeDue(reminder);

        assertThat(sender.sendDue(Instant.now())).isZero();

        assertThat(reminderMails(emailOf(userId))).isEmpty();
        getAs(me, "/applications/" + app + "/reminders").andExpect(jsonPath("$.items[0].state").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].cancelReason").value("EMAIL_DISABLED"));
    }

    @Test
    void anAccountThatCannotReceiveOptionalMailGetsNoReminderEither() throws Exception {
        Session me = newSession();
        UUID userId = userIdOf(me);
        String app = manual(me, "Dev");
        String reminder = idOf(addReminder(me, app, in(2), null));
        makeDue(reminder);
        jdbc.update("update users set email_verified_at = null where id = ?", userId);

        assertThat(sender.sendDue(Instant.now())).isZero();

        assertThat(reminderMails(emailOf(userId))).isEmpty();
        getAs(me, "/applications/" + app + "/reminders").andExpect(jsonPath("$.items[0].cancelReason").value("NO_RECIPIENT"));
    }

    @Test
    void aReminderOfAnApplicationThatWasClosedBehindTheSendersBackIsCancelledNotSent() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        String reminder = idOf(addReminder(me, app, in(2), null));
        makeDue(reminder);
        jdbc.update("update applications set status = 'REJECTED' where id = ?::uuid", app);

        assertThat(sender.sendDue(Instant.now())).isZero();

        assertThat(reminderMails(emailOf(userIdOf(me)))).isEmpty();
        assertThat(reminderState(reminder)).isEqualTo("CANCELLED");
    }

    @Test
    void aClaimWhoseSenderDiedIsTakenOverAfterTheRetryDelay() throws Exception {
        Session me = newSession();
        String app = manual(me, "Dev");
        String reminder = idOf(addReminder(me, app, in(2), null));
        makeDue(reminder);
        // a sender claimed it a minute ago and died: nobody else touches it yet
        jdbc.update("update reminders set claimed_at = now() - interval '1 minute', attempts = 1 where id = ?::uuid",
                reminder);
        assertThat(sender.sendDue(Instant.now())).isZero();

        // once the claim is stale it is sent
        jdbc.update("update reminders set claimed_at = now() - interval '1 hour' where id = ?::uuid", reminder);
        assertThat(sender.sendDue(Instant.now())).isEqualTo(1);
        assertThat(reminderMails(emailOf(userIdOf(me)))).hasSize(1);
    }

    @Test
    void theEmailsOfDifferentKindsSaySoAndOnlyReachTheirOwner() throws Exception {
        Session me = newSession();
        Session other = newSession();
        String app = manual(me, "Dev");
        makeDue(idOf(addReminder(me, app, in(2), "\"kind\":\"INTERVIEW\"")));
        makeDue(idOf(addReminder(me, app, in(2), "\"kind\":\"CUSTOM\"")));
        String othersApp = manual(other, "Other job");
        String others = idOf(addReminder(other, othersApp, in(2), null));

        assertThat(sender.sendDue(Instant.now())).isGreaterThanOrEqualTo(2);

        List<String> subjects = awaitReminders(emailOf(userIdOf(me)), 2).stream().map(Mail::subject).toList();
        assertThat(subjects).containsExactlyInAnyOrder("Reminder: interview for Dev at Acme", "Reminder: Dev at Acme");
        assertThat(reminderState(others)).isEqualTo("PENDING");
        assertThat(reminderMails(emailOf(userIdOf(other)))).isEmpty();
    }
}
