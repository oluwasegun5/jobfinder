package com.jobfinder.core.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.profile.ResumeTestSupport;

/**
 * The retention jobs against the real schema (docs/adr/0040): each module's data past its period goes, everything
 * inside it stays, and a deleted unverified account takes its data with it through the normal deletion path.
 */
class RetentionTests extends ResumeTestSupport {

    @Autowired
    private RetentionRunner runner;

    private static Timestamp ago(int days) {
        return Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private UUID newUserId() throws Exception {
        return userIdOf(newSession().accessToken());
    }

    private UUID source() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into sources (id, code, kind, created_at, updated_at) values (?, ?, 'ATS', now(), now())",
                id, "RET-" + id);
        return id;
    }

    private void rawPosting(UUID source, String external, int daysAgo) {
        jdbc.update("insert into raw_job_postings (id, source_id, external_id, payload, fetched_at, created_at, "
                + "updated_at) values (?, ?, ?, '{}'::jsonb, ?, now(), now())", UUID.randomUUID(), source, external,
                ago(daysAgo));
    }

    @Test
    void theSetOfRetentionTasksIsTheDocumentedOne() {
        assertThat(runner.taskNames()).containsExactlyInAnyOrder("raw-postings", "expired-tokens",
                "unverified-accounts", "webhook-events", "notification-log", "rendered-files");
    }

    @Test
    void rawPostingsOlderThanThirtyDaysAreDeletedAndNewerOnesStay() {
        UUID source = source();
        rawPosting(source, "old", 31);
        rawPosting(source, "recent", 29);
        rawPosting(source, "fresh", 0);

        Map<String, Integer> result = runner.runAll(Instant.now());

        assertThat(result.get("raw-postings")).isPositive();
        assertThat(jdbc.queryForList("select external_id from raw_job_postings where source_id = ?", String.class,
                source)).containsExactlyInAnyOrder("recent", "fresh");
    }

    @Test
    void expiredTokensPastTheGraceGoAndLiveOrRecentOnesStay() throws Exception {
        UUID user = newUserId();
        jdbc.update("delete from refresh_tokens where user_id = ?", user);
        jdbc.update("delete from email_tokens where user_id = ?", user);
        for (Object[] t : new Object[][] { { "gone-old---", -8 }, { "kept-recent", -1 }, { "kept-live---", 5 } }) {
            int expiresInDays = (Integer) t[1];
            jdbc.update("insert into refresh_tokens (id, user_id, token_hash, family_id, expires_at, created_at, "
                    + "updated_at) values (?, ?, ?, ?, ?, now(), now())", UUID.randomUUID(), user,
                    t[0] + UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID(),
                    Timestamp.from(Instant.now().plus(expiresInDays, ChronoUnit.DAYS)));
            jdbc.update("insert into email_tokens (id, user_id, type, token_hash, expires_at, created_at, updated_at) "
                    + "values (?, ?, 'RESET_PASSWORD', ?, ?, now(), now())", UUID.randomUUID(), user,
                    t[0] + UUID.randomUUID().toString().replace("-", ""),
                    Timestamp.from(Instant.now().plus(expiresInDays, ChronoUnit.DAYS)));
        }

        runner.runAll(Instant.now());

        for (String table : new String[] { "refresh_tokens", "email_tokens" }) {
            assertThat(jdbc.queryForList("select left(token_hash, 11) from " + table + " where user_id = ?",
                    String.class, user)).as(table).containsExactlyInAnyOrder("kept-recent", "kept-live--");
        }
    }

    @Test
    void anUnverifiedAccountPastThePeriodIsDeletedThroughTheNormalPathAndOthersStay() throws Exception {
        // Unverified: signed up, never clicked the link.
        String staleEmail = newEmail();
        postJson("/auth/signup", credentials(staleEmail, PASSWORD), newIp()).andReturn();
        UUID stale = jdbc.queryForObject("select id from users where email = ?", UUID.class, staleEmail);
        jdbc.update("update users set created_at = ? where id = ?", ago(31), stale);
        String freshEmail = newEmail();
        postJson("/auth/signup", credentials(freshEmail, PASSWORD), newIp()).andReturn();
        UUID fresh = jdbc.queryForObject("select id from users where email = ?", UUID.class, freshEmail);
        // Old but verified, and an old unverified admin: neither is touched.
        UUID verifiedOld = newUserId();
        jdbc.update("update users set created_at = ? where id = ?", ago(400), verifiedOld);
        String adminEmail = newEmail();
        postJson("/auth/signup", credentials(adminEmail, PASSWORD), newIp()).andReturn();
        UUID admin = jdbc.queryForObject("select id from users where email = ?", UUID.class, adminEmail);
        jdbc.update("update users set created_at = ?, role = 'ADMIN' where id = ?", ago(90), admin);
        assertThat(count("select count(*) from email_tokens where user_id = ?", stale)).isPositive();

        runner.runAll(Instant.now());

        assertThat(count("select count(*) from users where id = ?", stale)).isZero();
        assertThat(count("select count(*) from email_tokens where user_id = ?", stale)).isZero();
        assertThat(count("select count(*) from users where id in (?, ?, ?)", fresh, verifiedOld, admin)).isEqualTo(3);
    }

    @Test
    void webhookEventsPastTheirPeriodGoAndRecentOnesStay() {
        String oldId = "evt_old_" + UUID.randomUUID();
        String newId = "evt_new_" + UUID.randomUUID();
        jdbc.update("insert into webhook_events (provider, event_id, event_type, received_at) values "
                + "('STRIPE', ?, 'x', ?)", oldId, ago(181));
        jdbc.update("insert into webhook_events (provider, event_id, event_type, received_at) values "
                + "('STRIPE', ?, 'x', ?)", newId, ago(179));

        runner.runAll(Instant.now());

        assertThat(count("select count(*) from webhook_events where event_id = ?", oldId)).isZero();
        assertThat(count("select count(*) from webhook_events where event_id = ?", newId)).isEqualTo(1);
    }

    @Test
    void theEmailLogKeepsPendingRowsAndRowsInsideThePeriod() throws Exception {
        UUID user = newUserId();
        for (Object[] row : new Object[][] { { "oldsent", "SENT", 366 }, { "oldpending", "PENDING", 366 },
                { "newsent", "SENT", 10 } }) {
            jdbc.update("insert into notification_log (id, user_id, kind, scope, window_key, status, created_at, "
                    + "updated_at) values (?, ?, 'FOR_YOU_DIGEST', '', ?, ?, ?, ?)", UUID.randomUUID(), user, row[0],
                    row[1], ago((Integer) row[2]), ago((Integer) row[2]));
        }

        runner.runAll(Instant.now());

        assertThat(jdbc.queryForList("select window_key from notification_log where user_id = ?", String.class, user))
                .containsExactlyInAnyOrder("oldpending", "newsent");
    }

    @Test
    void renderedFilesPastThePeriodAreRemovedFromStorageAndFromTheIndex() throws Exception {
        UUID user = newUserId();
        String oldKey = "renders/" + user + "/old-" + UUID.randomUUID() + ".pdf";
        String newKey = "renders/" + user + "/new-" + UUID.randomUUID() + ".pdf";
        for (Object[] f : new Object[][] { { oldKey, 91 }, { newKey, 5 } }) {
            putObject((String) f[0]);
            jdbc.update("insert into rendered_files (id, user_id, source_type, source_id, content_sha256, "
                    + "renderer_version, template, format, page_size, storage_key, file_sha256, size_bytes, "
                    + "created_at) values (?, ?, 'DOCUMENT', ?, ?, 1, 'ATS', 'PDF', 'A4', ?, ?, 10, ?)",
                    UUID.randomUUID(), user, UUID.randomUUID(), "a".repeat(64), f[0], "b".repeat(64),
                    ago((Integer) f[1]));
        }

        runner.runAll(Instant.now());

        assertThat(objectExists(oldKey)).isFalse();
        assertThat(objectExists(newKey)).isTrue();
        assertThat(jdbc.queryForList("select storage_key from rendered_files where user_id = ?", String.class, user))
                .containsExactly(newKey);
    }
}
