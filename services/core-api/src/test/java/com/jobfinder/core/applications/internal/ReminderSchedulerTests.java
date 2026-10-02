package com.jobfinder.core.applications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * The reminder clock (docs/adr/0032-application-tracker.md): the job sends what is due under the ShedLock lock, runs on
 * one instance at a time, and sends each reminder once even if two instances do run together. The scheduler bean is off in
 * tests so nothing fires on its own: these tests build it and run it by hand.
 */
class ReminderSchedulerTests extends ApplicationsTestSupport {

    @Autowired
    private ReminderService reminders;
    @Autowired
    private LockingTaskExecutor locks;
    @Autowired
    private ApplicationsProperties properties;

    private ReminderScheduler instance() {
        return new ReminderScheduler(reminders, locks, properties);
    }

    @BeforeEach
    void releaseTheLock() {
        // Expire it rather than delete it: ShedLock remembers a row exists and would only ever update it.
        jdbc.update("update shedlock set lock_until = now() - interval '1 hour' where name = ?",
                ReminderScheduler.LOCK_NAME);
    }

    private String dueReminder(Session me) throws Exception {
        String reminder = idOf(addReminder(me, manual(me, "Dev"), Instant.now().plus(Duration.ofDays(2)), null));
        makeDue(reminder);
        return reminder;
    }

    @Test
    void aRunSendsTheDueReminderUnderTheNamedLock() throws Exception {
        Session me = newSession();
        String reminder = dueReminder(me);

        assertThat(instance().run(Instant.now())).isTrue();

        assertThat(reminderState(reminder)).isEqualTo("SENT");
        assertThat(count("select count(*) from shedlock where name = ?", ReminderScheduler.LOCK_NAME)).isEqualTo(1);
        assertThat(mails(emailOf(userIdOf(me))).stream().filter(m -> m.subject().startsWith("Reminder"))).hasSize(1);
    }

    @Test
    void anInstanceThatFindsTheLockHeldDoesNothing() throws Exception {
        Session me = newSession();
        String reminder = dueReminder(me);
        jdbc.update("""
                insert into shedlock (name, lock_until, locked_at, locked_by)
                values (?, now() + interval '1 hour', now(), 'another-instance')
                on conflict (name) do update set lock_until = excluded.lock_until, locked_by = excluded.locked_by
                """, ReminderScheduler.LOCK_NAME);

        assertThat(instance().run(Instant.now())).isFalse();

        assertThat(reminderState(reminder)).isEqualTo("PENDING");
    }

    @Test
    void twoInstancesRunningTogetherSendEachReminderOnce() throws Exception {
        Session me = newSession();
        String address = emailOf(userIdOf(me));
        for (int i = 0; i < 6; i++) {
            dueReminder(me);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> runs = List.of(pool.submit(() -> {
                go.await();
                return instance().run(Instant.now());
            }), pool.submit(() -> {
                go.await();
                return instance().run(Instant.now());
            }));
            go.countDown();
            for (Future<Boolean> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<Mail> sent = awaitMail(address, 7).stream().filter(m -> m.subject().startsWith("Reminder")).toList();
        assertThat(sent).hasSize(6);
        assertThat(count("select count(*) from reminders where user_id = ? and state = 'SENT'", userIdOf(me)))
                .isEqualTo(6);
    }

    @Test
    void theSchedulerIsOffInTestsSoNothingFiresByItself() {
        assertThat(contextHasScheduler()).isFalse();
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    private boolean contextHasScheduler() {
        return context.getBeanNamesForType(ReminderScheduler.class).length > 0;
    }
}
