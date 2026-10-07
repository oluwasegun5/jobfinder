package com.jobfinder.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.profile.ResumeTestSupport;

/**
 * The deletion guarantee (docs/adr/0040), asserted for the whole schema rather than module by module:
 *
 * <ol>
 * <li>from the database catalog, every table that holds user data is erased by the user row's deletion (cascade), with
 * the exceptions listed here and explained;</li>
 * <li>every such table is also accounted for in the data export, so a new table cannot ship without a decision on both;</li>
 * <li>a user with data in every module is deleted through the API, and no row, file or Redis key is left, while a
 * bystander's data is exactly as before.</li>
 * </ol>
 */
class AccountDeletionCompletenessTests extends ResumeTestSupport {

    /** Foreign keys into user-owned data that do not cascade, and why that is right. */
    private static final Map<String, String> NON_CASCADING = Map.of(
            "ai_calls.user_id", "SET NULL: the cost history (model, tokens, cost; no content) stays, without the person",
            "credit_ledger.ai_call_id", "NO ACTION: the ledger rows are deleted first by BillingDeletionHandler",
            "notification_log.saved_search_id", "SET NULL: the log row is itself deleted through its own user_id");

    /** Every user-owned table and the export file that carries its content, or the reason it is not exported. */
    private static final Map<String, String> EXPORT = Map.ofEntries(
            Map.entry("users", "account/account.json"),
            Map.entry("oauth_accounts", "account/sign-in-providers.json"),
            Map.entry("profiles", "profile/profile.json"),
            Map.entry("preferences", "profile/preferences.json"),
            Map.entry("resumes", "profile/resumes.json"),
            Map.entry("resume_versions", "profile/resume-versions.json"),
            Map.entry("generated_documents", "documents/documents.json"),
            Map.entry("application_packs", "documents/application-packs.json"),
            Map.entry("rendered_files", "generated-files/files.json"),
            Map.entry("applications", "applications/applications.json"),
            Map.entry("application_events", "applications/events.json"),
            Map.entry("reminders", "applications/reminders.json"),
            Map.entry("interview_prep", "interview/prep.json"),
            Map.entry("interview_questions", "interview/prep-questions.json"),
            Map.entry("company_briefs", "interview/company-briefs.json"),
            Map.entry("interview_sessions", "interview/mock-sessions.json"),
            Map.entry("interview_turns", "interview/mock-turns.json"),
            Map.entry("user_job_actions", "jobs/job-actions.json"),
            Map.entry("match_scores", "matching/match-scores.json"),
            Map.entry("saved_searches", "notifications/saved-searches.json"),
            Map.entry("notification_preferences", "notifications/email-settings.json"),
            Map.entry("notification_log", "notifications/email-log.json"),
            Map.entry("subscriptions", "billing/subscriptions.json"),
            Map.entry("credit_ledger", "billing/credit-ledger.json"),
            Map.entry("ai_calls", "billing/ai-calls.json"));

    private static final Map<String, String> NOT_EXPORTED = Map.of(
            "refresh_tokens", "session secrets (hashes) and no personal data of their own",
            "email_tokens", "single-use link secrets (hashes) and no personal data of their own");

    @Autowired
    private AiUsageGate gate;

    private record Edge(String child, String parent, char rule, String columns) {
    }

    private List<Edge> foreignKeys() {
        return jdbc.query("""
                select c.conrelid::regclass::text as child, c.confrelid::regclass::text as parent,
                       c.confdeltype as rule,
                       (select string_agg(a.attname, ',' order by a.attnum) from pg_attribute a
                         where a.attrelid = c.conrelid and a.attnum = any (c.conkey)) as columns
                  from pg_constraint c where c.contype = 'f' and c.connamespace = 'public'::regnamespace
                """, (rs, n) -> new Edge(rs.getString("child"), rs.getString("parent"), rs.getString("rule").charAt(0),
                rs.getString("columns")));
    }

    /** Tables with a foreign-key path to users: the tables that can hold a person's data. */
    private Set<String> ownedTables(List<Edge> edges) {
        Set<String> owned = new TreeSet<>(Set.of("users"));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Edge e : edges) {
                if (owned.contains(e.parent()) && owned.add(e.child())) {
                    grew = true;
                }
            }
        }
        return owned;
    }

    @Test
    void everyForeignKeyIntoUserOwnedDataCascadesExceptTheDocumentedOnes() {
        List<Edge> edges = foreignKeys();
        Set<String> owned = ownedTables(edges);
        List<String> violations = new ArrayList<>();
        for (Edge e : edges) {
            String key = e.child() + "." + e.columns();
            if (owned.contains(e.parent()) && e.rule() != 'c' && !NON_CASCADING.containsKey(key)) {
                violations.add(key + " -> " + e.parent() + " is '" + e.rule() + "', not CASCADE");
            }
        }
        assertThat(violations).as("user data that would survive the deletion of its owner").isEmpty();
        assertThat(NON_CASCADING.keySet()).as("stale entries in the exception list")
                .allMatch(key -> edges.stream().anyMatch(e -> (e.child() + "." + e.columns()).equals(key)));
    }

    @Test
    void noColumnThatNamesAUserEscapesTheForeignKeys() {
        Set<String> keyed = new HashSet<>();
        for (Edge e : foreignKeys()) {
            if (e.parent().equals("users")) {
                keyed.add(e.child() + "." + e.columns());
            }
        }
        List<String> loose = jdbc.queryForList("""
                select table_name || '.' || column_name from information_schema.columns
                 where table_schema = 'public' and table_name <> 'users'
                   and column_name in ('user_id', 'owner_id', 'created_by', 'account_id', 'candidate_id', 'email')
                """, String.class);
        assertThat(loose.stream().filter(c -> !keyed.contains(c)).toList())
                .as("columns that identify a user without a foreign key to users").isEmpty();
    }

    @Test
    void everyUserOwnedTableIsEitherExportedOrExplicitlyNotExported() {
        Set<String> owned = ownedTables(foreignKeys());
        Set<String> decided = new TreeSet<>(EXPORT.keySet());
        decided.addAll(NOT_EXPORTED.keySet());

        assertThat(decided).as("tables with user data and no export decision (add an exporter or explain why not)")
                .isEqualTo(owned);
    }

    private Map<String, byte[]> export(Session session) throws Exception {
        MvcResult started = mvc.perform(get("/me/export").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(done.getResponse().getContentAsByteArray()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    /** Gives the user a row in as many modules as can be seeded cheaply; returns their resumes' ids. */
    private List<UUID> seed(Session session, UUID userId) throws Exception {
        List<UUID> resumes = List.of(uploadPdf(session), uploadPdf(session));
        // The parse runs on a queue worker and bills the user when it finishes: wait, so the rows below are stable.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(60)).until(() -> count(
                "select count(*) from resumes where user_id = ? and parse_status = 'PENDING'", userId) == 0);
        jdbc.update("insert into profiles (id, user_id, full_name, created_at, updated_at) "
                + "values (?, ?, 'Erase Me', now(), now())", UUID.randomUUID(), userId);
        jdbc.update("insert into preferences (id, user_id, target_titles, created_at, updated_at) "
                + "values (?, ?, array['Engineer'], now(), now())", UUID.randomUUID(), userId);
        jdbc.update("insert into notification_preferences (user_id, created_at, updated_at) values (?, now(), now())",
                userId);
        jdbc.update("insert into notification_log (id, user_id, kind, scope, window_key, status, created_at, "
                + "updated_at) values (?, ?, 'FOR_YOU_DIGEST', '', 'w1', 'SENT', now(), now())", UUID.randomUUID(),
                userId);
        jdbc.update("insert into saved_searches (id, user_id, name, last_run_at, created_at, updated_at) "
                + "values (?, ?, 'Erase search', now(), now(), now())", UUID.randomUUID(), userId);
        UUID app = UUID.randomUUID();
        jdbc.update("insert into applications (id, user_id, title, status, status_changed_at, created_at, updated_at) "
                + "values (?, ?, 'Erase Me Inc role', 'APPLIED', now(), now(), now())", app, userId);
        jdbc.update("insert into application_events (id, application_id, user_id, to_status, at) "
                + "values (?, ?, ?, 'APPLIED', now())", UUID.randomUUID(), app, userId);
        jdbc.update("insert into reminders (id, application_id, user_id, kind, due_at, created_at, updated_at) "
                + "values (?, ?, ?, 'CUSTOM', now() + interval '30 days', now(), now())", UUID.randomUUID(), app, userId);
        jdbc.update("insert into oauth_accounts (id, user_id, provider, provider_user_id, created_at, updated_at) "
                + "values (?, ?, 'GOOGLE', ?, now(), now())", UUID.randomUUID(), userId, "sub-" + userId);
        String renderKey = "renders/" + userId + "/doc/ats-a4-" + UUID.randomUUID() + ".pdf";
        putObject(renderKey);
        jdbc.update("insert into rendered_files (id, user_id, source_type, source_id, content_sha256, "
                + "renderer_version, template, format, page_size, storage_key, file_sha256, size_bytes, created_at) "
                + "values (?, ?, 'DOCUMENT', ?, ?, 1, 'ATS', 'PDF', 'A4', ?, ?, 10, now())", UUID.randomUUID(), userId,
                UUID.randomUUID(), "a".repeat(64), renderKey, "b".repeat(64));
        // A grant (credit ledger) and an AI call attributed to the user (cost history).
        gate.requireAllowance(userId, "parse_resume");
        jdbc.update("insert into ai_calls (id, request_key, user_id, feature, provider, model, status, created_at) "
                + "values (?, ?, ?, 'tailor_resume', 'p', 'm', 'SUCCEEDED', ?)", UUID.randomUUID(),
                "erase-" + UUID.randomUUID(), userId, ts(Instant.now()));
        return resumes;
    }

    private Map<String, Integer> rowsOwnedBy(UUID userId) {
        Map<String, Integer> rows = new TreeMap<>();
        for (Edge e : foreignKeys()) {
            if (e.parent().equals("users")) {
                rows.put(e.child() + "." + e.columns(), count("select count(*) from " + e.child() + " where "
                        + e.columns() + " = ?", userId));
            }
        }
        return rows;
    }

    @Test
    void deletingAUserWithDataInEveryModuleLeavesNoRowFileOrKeyAndNobodyElsesDataMoves() throws Exception {
        Session bystander = newSession();
        UUID bystanderId = userIdOf(bystander.accessToken());
        seed(bystander, bystanderId);
        Map<String, Integer> bystanderBefore = rowsOwnedBy(bystanderId);

        Session leaving = newSession();
        UUID userId = userIdOf(leaving.accessToken());
        List<UUID> resumes = seed(leaving, userId);
        Map<String, Integer> before = rowsOwnedBy(userId);
        Map<String, byte[]> zip = export(leaving);
        for (var table : EXPORT.entrySet()) {
            assertThat(zip.keySet()).as("export file of " + table.getKey())
                    .contains("jobfinder-export/" + table.getValue());
        }
        assertThat(before.values().stream().filter(n -> n > 0).count()).as("tables seeded for the user")
                .isGreaterThanOrEqualTo(14);
        assertThat(objectsUnder("resumes/" + userId + "/")).isEqualTo(2);
        assertThat(objectsUnder("renders/" + userId + "/")).isEqualTo(1);
        assertThat(redisKeysFor(userId)).isPositive();
        int versionsBefore = count("select count(*) from resume_versions where resume_id in (?, ?)",
                resumes.get(0), resumes.get(1));
        assertThat(versionsBefore).isPositive();

        mvc.perform(delete("/me").header("Authorization", "Bearer " + leaving.accessToken()))
                .andExpect(status().isNoContent());

        // Every table that keys rows by this user: nothing left, except the anonymised cost history.
        Map<String, Integer> after = rowsOwnedBy(userId);
        assertThat(after.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList())
                .as("tables still holding rows of the deleted user").isEmpty();
        assertThat(count("select count(*) from users where id = ?", userId)).isZero();
        assertThat(count("select count(*) from resume_versions where resume_id in (?, ?)", resumes.get(0),
                resumes.get(1))).isZero();
        assertThat(count("select count(*) from ai_calls where feature = 'tailor_resume' and user_id is null "
                + "and request_key like 'erase-%'")).isPositive();
        assertThat(objectsUnder("resumes/" + userId + "/")).isZero();
        assertThat(objectsUnder("renders/" + userId + "/")).isZero();
        assertThat(redisKeysFor(userId)).isZero();
        // The bystander is exactly as before.
        assertThat(rowsOwnedBy(bystanderId)).isEqualTo(bystanderBefore);
        assertThat(objectsUnder("resumes/" + bystanderId + "/")).isEqualTo(2);
        assertThat(objectsUnder("renders/" + bystanderId + "/")).isEqualTo(1);
        assertThat(new HashMap<>(before)).isNotEmpty();
    }

    private long redisKeysFor(UUID userId) {
        try (var client = io.lettuce.core.RedisClient.create(redisUri); var connection = client.connect()) {
            return connection.sync().keys("rl:*:" + userId).size();
        }
    }

    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.redis-uri}")
    private String redisUri;
}
