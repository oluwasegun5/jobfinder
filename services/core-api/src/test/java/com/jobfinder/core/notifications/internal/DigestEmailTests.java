package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.notifications.internal.DigestService.Summary;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchCriteria;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchFrequency;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchRequest;
import com.jobfinder.core.notifications.internal.NotificationDtos.UnsubscribeScope;

/**
 * The done-when of P3.4: the digest job sends the right emails to Mailpit (a real SMTP server in a container). Right
 * recipient and subject, only new and strong matches, links to the web app, unsubscribe link and List-Unsubscribe headers,
 * no duplicate on a re-run, nothing sent when nothing is new or the user must not be mailed, daily against weekly,
 * dates in the user's zone, and hostile job text kept out of the HTML.
 */
class DigestEmailTests extends NotificationsTestSupport {

    @Autowired
    private UnsubscribeTokens tokens;

    /** A user with three jobs scored 92, 81 and 55, a daily digest due at {@code now}. */
    private record World(Account me, UUID best, UUID good, UUID weak) {
    }

    private World world(Instant now) {
        Account me = onboarded();
        UUID best = strongJob("Java Backend Engineer", "Globex");
        UUID good = strongJob("Platform Engineer", "Initech");
        UUID weak = strongJob("Office Manager", "Umbrella");
        score(me.id(), scores(best, 92, good, 81, weak, 55));
        dailyDigestAt(me.id(), now);
        return new World(me, best, good, weak);
    }

    // --- the done-when ---

    @Test
    void theDigestGoesToTheUserWithOnlyTheirNewStrongMatchesAndTheRightLinks() {
        Instant now = Instant.now();
        World w = world(now);
        int modelCalls = scoreRequestCount(w.me().id());

        Summary summary = digests.runDue(now);

        List<Message> sent = messages(w.me().email());
        assertThat(sent).hasSize(1);
        Message m = sent.get(0);
        assertThat(summary.sent()).isGreaterThanOrEqualTo(1);
        assertThat(m.subject()).isEqualTo("2 new strong matches on JobFinder");
        // the two strong matches, best first, each linking to its page in the web app; the weak one is left out
        assertThat(m.text()).contains(WEB + "/jobs/" + w.best(), WEB + "/jobs/" + w.good(), "Java Backend Engineer",
                "Globex", "Match 92/100", "Match 81/100").doesNotContain("Office Manager", w.weak().toString());
        assertThat(m.text().indexOf(w.best().toString())).isLessThan(m.text().indexOf(w.good().toString()));
        assertThat(m.html()).contains("href=\"" + WEB + "/jobs/" + w.best() + "\"", "Java Backend Engineer")
                .doesNotContain("Office Manager");
        // an unsubscribe link in the body (to the web page) and the one-click headers (to the API)
        assertThat(m.text()).contains(WEB + "/unsubscribe?token=");
        assertThat(m.html()).contains(WEB + "/unsubscribe?token=");
        assertThat(m.header("List-Unsubscribe")).startsWith("<" + WEB + "/api/core/notifications/unsubscribe/")
                .endsWith(">");
        assertThat(m.header("List-Unsubscribe-Post")).isEqualTo("List-Unsubscribe=One-Click");
        assertThat(tokens.verify(m.oneClickToken())).get().satisfies(claims -> {
            assertThat(claims.userId()).isEqualTo(w.me().id());
            assertThat(claims.scope()).isEqualTo(UnsubscribeScope.DIGESTS);
        });
        // audit: what was listed is recorded, and the digest never asked the model for anything
        assertThat(jdbc.queryForList("select unnest(job_ids) from notification_log where user_id = ? and status = 'SENT'",
                UUID.class, w.me().id())).containsExactlyInAnyOrder(w.best(), w.good());
        assertThat(scoreRequestCount(w.me().id())).isEqualTo(modelCalls);
    }

    @Test
    void aRerunInTheSameWindowSendsNothingMore() {
        Instant now = Instant.now();
        World w = world(now);

        digests.runDue(now);
        digests.runDue(now);
        digests.runDue(now.plus(Duration.ofMinutes(30)));

        assertThat(messages(w.me().email())).hasSize(1);
        assertThat(logRows(w.me().id(), "FOR_YOU_DIGEST", "SENT")).isEqualTo(1);
    }

    @Test
    void tomorrowsDigestHoldsOnlyWhatIsNewSinceAndAnEmptyDayIsSkipped() {
        Instant now = Instant.now();
        World w = world(now);
        digests.runDue(now);

        UUID fresh = strongJob("Kotlin Engineer", "Hooli");
        score(w.me().id(), scores(w.best(), 92, w.good(), 81, w.weak(), 55, fresh, 88));
        digests.runDue(now.plus(Duration.ofDays(1)));

        List<Message> sent = messages(w.me().email());
        assertThat(sent).hasSize(2);
        Message second = sent.get(0); // newest first
        assertThat(second.subject()).isEqualTo("1 new strong match on JobFinder");
        assertThat(second.text()).contains(WEB + "/jobs/" + fresh, "Kotlin Engineer")
                .doesNotContain(w.best().toString(), w.good().toString());

        digests.runDue(now.plus(Duration.ofDays(2)));

        assertThat(messages(w.me().email())).hasSize(2);
        assertThat(logRows(w.me().id(), "FOR_YOU_DIGEST", "SKIPPED")).isEqualTo(1);
    }

    @Test
    void aJobTheUserHidOrAppliedToIsNeverInTheDigest() {
        Instant now = Instant.now();
        World w = world(now);
        jdbc.update("insert into user_job_actions (user_id, job_id, action, created_at) values (?, ?, 'HIDDEN', now())",
                w.me().id(), w.best());

        digests.runDue(now);

        Message m = messages(w.me().email()).get(0);
        assertThat(m.subject()).isEqualTo("1 new strong match on JobFinder");
        assertThat(m.text()).contains(w.good().toString()).doesNotContain(w.best().toString());
    }

    // --- who is never mailed ---

    @Test
    void nobodyIsMailedWhoIsUnverifiedDisabledDeletedOrHasSwitchedMailOff() {
        Instant now = Instant.now();
        World unverified = world(now);
        jdbc.update("update users set email_verified_at = null where id = ?", unverified.me().id());
        World disabled = world(now);
        jdbc.update("update users set status = 'DISABLED' where id = ?", disabled.me().id());
        World deleted = world(now);
        jdbc.update("update users set deleted_at = now() where id = ?", deleted.me().id());
        World digestsOff = world(now);
        jdbc.update("update notification_preferences set digests_unsubscribed_at = now() where user_id = ?",
                digestsOff.me().id());
        World allOff = world(now);
        jdbc.update("update notification_preferences set marketing_unsubscribed_at = now() where user_id = ?",
                allOff.me().id());
        World emailOff = world(now);
        jdbc.update("update notification_preferences set email_enabled = false where user_id = ?", emailOff.me().id());
        World digestSwitchedOff = world(now);
        jdbc.update("update notification_preferences set digest_enabled = false where user_id = ?",
                digestSwitchedOff.me().id());
        World control = world(now);

        digests.runDue(now);

        for (World w : List.of(unverified, disabled, deleted, digestsOff, allOff, emailOff, digestSwitchedOff)) {
            assertThat(messages(w.me().email())).as(w.me().email()).isEmpty();
            assertThat(count("select count(*) from notification_log where user_id = ?", w.me().id())).isZero();
        }
        assertThat(messages(control.me().email())).hasSize(1);
    }

    // --- cadence and time zones ---

    @Test
    void aWeeklyDigestGoesOutOnTheUsersWeekdayAndNotOnOthers() {
        Instant now = Instant.now();
        int today = now.atZone(java.time.ZoneOffset.UTC).getDayOfWeek().getValue();
        World onDay = world(now);
        settings(onDay.me().id(), true, "WEEKLY", utcHour(now), today, null, false, 85);
        World otherDay = world(now);
        settings(otherDay.me().id(), true, "WEEKLY", utcHour(now), today % 7 + 1, null, false, 85);
        World daily = world(now);

        digests.runDue(now);

        assertThat(messages(onDay.me().email())).hasSize(1);
        assertThat(messages(otherDay.me().email())).isEmpty();
        assertThat(messages(daily.me().email())).hasSize(1);
        // and a week later the weekly one goes out again, with what is new
        UUID fresh = strongJob("Staff Engineer", "Initrode");
        score(onDay.me().id(), scores(onDay.best(), 92, onDay.good(), 81, onDay.weak(), 55, fresh, 90));
        digests.runDue(now.plus(Duration.ofDays(3)));
        assertThat(messages(onDay.me().email())).hasSize(1);
        digests.runDue(now.plus(Duration.ofDays(7)));
        List<Message> weekly = messages(onDay.me().email());
        assertThat(weekly).hasSize(2);
        assertThat(weekly.get(0).text()).contains(fresh.toString());
    }

    @Test
    void aDigestIsNotSentAtTheWrongHourOrTooLate() {
        Instant now = Instant.now();
        World early = world(now);
        settings(early.me().id(), true, "DAILY", utcHour(now.plus(Duration.ofHours(2))), 1, null, false, 85);
        World late = world(now);
        settings(late.me().id(), true, "DAILY", utcHour(now.minus(Duration.ofHours(8))), 1, null, false, 85);

        digests.runDue(now);

        assertThat(messages(early.me().email())).isEmpty();
        assertThat(messages(late.me().email())).isEmpty();
        // but two hours after the first one's hour has come, it is sent
        digests.runDue(now.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(5)));
        assertThat(messages(early.me().email())).hasSize(1);
        assertThat(messages(late.me().email())).isEmpty();
    }

    @Test
    void theHourIsTheUsersOwnAndTheDatesInTheEmailAreInTheirZone() {
        Instant now = Instant.now();
        // Lagos is UTC+1 all year: its local hour is one more than UTC's.
        World lagos = world(now);
        settings(lagos.me().id(), true, "DAILY", (utcHour(now) + 1) % 24, 1, "Africa/Lagos", false, 85);
        // Los Angeles is 7 or 8 hours behind UTC: the same number is a different moment there, so not due now.
        World la = world(now);
        settings(la.me().id(), true, "DAILY", utcHour(now), 1, "America/Los_Angeles", false, 85);

        digests.runDue(now);

        assertThat(messages(la.me().email())).isEmpty();
        List<Message> sent = messages(lagos.me().email());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).text()).contains("(Africa/Lagos)");
        assertThat(sent.get(0).html()).contains("(Africa/Lagos)");
        // a user with no zone gets UTC, and says so
        World utc = world(now);
        digests.runDue(now.plus(Duration.ofSeconds(1)));
        assertThat(messages(utc.me().email()).get(0).text()).contains("(UTC)");
    }

    // --- content safety ---

    @Test
    void hostileJobTextCannotInjectHtmlOrHeaders() {
        Instant now = Instant.now();
        Account me = onboarded();
        UUID evil = strongJob("<script>alert('x')</script> Engineer\r\nBcc: attacker@example.test",
                "Evil & \"Co\" <img src=x onerror=alert(1)>");
        score(me.id(), scores(evil, 95));
        dailyDigestAt(me.id(), now);

        digests.runDue(now);

        Message m = messages(me.email()).get(0);
        assertThat(m.html()).doesNotContain("<script>", "<img", "onerror=alert(1)>").contains("&lt;script&gt;",
                "Evil &amp; &quot;Co&quot; &lt;img");
        assertThat(m.text()).doesNotContain("\nBcc:");
        assertThat(m.headers()).doesNotContainKey("Bcc");
        assertThat(m.subject()).doesNotContain("script");
    }

    @Test
    void anAggregatorsCreditAppearsWithTheJobsItListed() {
        Instant now = Instant.now();
        Account me = onboarded();
        UUID job = strongJob("Rust Engineer", "Aggregated Inc");
        UUID source = jdbc.queryForObject(
                "select id from sources where attribution_name is not null order by code limit 1", UUID.class);
        String name = jdbc.queryForObject("select attribution_name from sources where id = ?", String.class, source);
        String text = jdbc.queryForObject("select attribution_text from sources where id = ?", String.class, source);
        jdbc.update("insert into job_sources (id, job_id, source_id, external_id, url, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, now(), now())", UUID.randomUUID(), job, source, "ext-" + job,
                "https://listing.example.test/" + job);
        score(me.id(), scores(job, 90));
        dailyDigestAt(me.id(), now);

        digests.runDue(now);

        Message m = messages(me.email()).get(0);
        assertThat(m.text()).contains("via " + name, text);
        assertThat(m.html()).contains("via " + name);
    }

    // --- saved searches ---

    private SavedSearchService.Row savedSearch(Account me, String name, String q, SavedSearchFrequency frequency) {
        UUID id = savedSearches.create(me.id(), new SavedSearchRequest(name,
                new SavedSearchCriteria(q, null, null, null, null, null, null, null), frequency)).id();
        return savedSearches.find(me.id(), id).orElseThrow();
    }

    private UUID newJob(String title) {
        return insert(spec().title(title).postedAt(Instant.now().plusMillis(50)));
    }

    /** Far enough ahead that the jobs just inserted are past the settle delay. */
    private static Instant later() {
        return Instant.now().plus(Duration.ofMinutes(10));
    }

    @Test
    void aSavedSearchDigestListsTheNewActiveMatchingJobsAndNothingElse() {
        Instant now = later();
        Account me = verified();
        UUID old = insert(spec().title("Kotlin Veteran").postedAt(Instant.now().minus(Duration.ofDays(2))));
        SavedSearchService.Row search = savedSearch(me, "Kotlin jobs", "kotlin", SavedSearchFrequency.DAILY);
        UUID first = newJob("Kotlin Backend Developer");
        UUID second = newJob("Senior Kotlin Engineer");
        UUID other = newJob("Java Developer");
        UUID expired = insert(spec().title("Kotlin Android").status("EXPIRED").postedAt(Instant.now().plusMillis(50)));
        UUID hidden = newJob("Kotlin Hidden Role");
        jdbc.update("insert into user_job_actions (user_id, job_id, action, created_at) values (?, ?, 'HIDDEN', now())",
                me.id(), hidden);
        settings(me.id(), false, "DAILY", utcHour(now), 1, null, false, 85);

        digests.runDue(now);

        List<Message> sent = messages(me.email());
        assertThat(sent).hasSize(1);
        Message m = sent.get(0);
        assertThat(m.subject()).isEqualTo("2 new jobs for \"Kotlin jobs\"");
        assertThat(m.text()).contains(WEB + "/jobs/" + first, WEB + "/jobs/" + second)
                .doesNotContain(old.toString(), other.toString(), expired.toString(), hidden.toString());
        assertThat(tokens.verify(m.oneClickToken())).get().satisfies(claims -> {
            assertThat(claims.scope()).isEqualTo(UnsubscribeScope.SAVED_SEARCH);
            assertThat(claims.savedSearchId()).isEqualTo(search.id());
        });
        assertThat(m.header("List-Unsubscribe-Post")).isEqualTo("List-Unsubscribe=One-Click");

        // a re-run sends nothing; the next day sends only what arrived since
        digests.runDue(now);
        assertThat(messages(me.email())).hasSize(1);
        // stored after the first run's cut-off (its "now" was ten minutes ahead of the clock the jobs are stamped with)
        UUID third = insert(spec().title("Kotlin Multiplatform Lead").postedAt(now.plus(Duration.ofMinutes(9))));
        digests.runDue(now.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(30)));
        List<Message> after = messages(me.email());
        assertThat(after).hasSize(2);
        assertThat(after.get(0).subject()).isEqualTo("1 new job for \"Kotlin jobs\"");
        assertThat(after.get(0).text()).contains(third.toString()).doesNotContain(first.toString(), second.toString());
    }


    @Test
    void whenThereAreMoreThanTheEmailListsItSaysHowManyMoreAndLinksToTheSearch() {
        Instant now = later();
        Account me = verified();
        savedSearch(me, "Many", "kotlin", SavedSearchFrequency.DAILY);
        for (int i = 0; i < 12; i++) {
            newJob("Kotlin Engineer " + i);
        }
        settings(me.id(), false, "DAILY", utcHour(now), 1, null, false, 85);

        digests.runDue(now);

        Message m = messages(me.email()).get(0);
        assertThat(m.subject()).isEqualTo("12 new jobs for \"Many\"");
        assertThat(m.text()).contains("and 2 more: " + WEB + "/jobs?q=kotlin");
        assertThat(m.text().split("/jobs/").length - 1).isEqualTo(10);
    }

    @Test
    void aSavedSearchWithNothingNewSendsNothingAndAdvancesItsWatermark() {
        Instant now = later();
        Account me = verified();
        SavedSearchService.Row search = savedSearch(me, "Rust jobs", "rust", SavedSearchFrequency.DAILY);
        settings(me.id(), false, "DAILY", utcHour(now), 1, null, false, 85);

        digests.runDue(now);

        assertThat(messages(me.email())).isEmpty();
        assertThat(logRows(me.id(), "SAVED_SEARCH_DIGEST", "SKIPPED")).isEqualTo(1);
        assertThat(savedSearches.find(me.id(), search.id()).orElseThrow().lastRunAt()).isAfter(search.lastRunAt());
    }

    @Test
    void savedSearchesFollowTheirOwnFrequencyAndOffMeansOff() {
        Instant now = later();
        int today = now.atZone(java.time.ZoneOffset.UTC).getDayOfWeek().getValue();
        Account me = verified();
        savedSearch(me, "Daily one", "alpha", SavedSearchFrequency.DAILY);
        savedSearch(me, "Weekly one", "beta", SavedSearchFrequency.WEEKLY);
        savedSearch(me, "Off one", "gamma", SavedSearchFrequency.OFF);
        UUID a = newJob("Alpha Engineer");
        UUID b = newJob("Beta Engineer");
        UUID g = newJob("Gamma Engineer");

        // the user's weekday is not today: only the daily one is due
        settings(me.id(), false, "DAILY", utcHour(now), today % 7 + 1, null, false, 85);
        digests.runDue(now);
        List<Message> sent = messages(me.email());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).subject()).isEqualTo("1 new job for \"Daily one\"");

        // on their weekday the weekly one goes too, still never the one that is off
        settings(me.id(), false, "DAILY", utcHour(now), today, null, false, 85);
        digests.runDue(now.plusSeconds(1));
        sent = messages(me.email());
        assertThat(sent).extracting(Message::subject).containsExactlyInAnyOrder("1 new job for \"Daily one\"",
                "1 new job for \"Weekly one\"");
        assertThat(sent.stream().map(Message::text).toList()).noneMatch(t -> t.contains(g.toString()));
        assertThat(sent.stream().map(Message::text).toList()).anyMatch(t -> t.contains(b.toString()));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void aSearchNameWithMarkupIsEscapedInTheEmail() {
        Instant now = later();
        Account me = verified();
        savedSearch(me, "<b>Bold</b> & \"quoted\"", "hostile", SavedSearchFrequency.DAILY);
        newJob("Hostile Engineer");
        settings(me.id(), false, "DAILY", utcHour(now), 1, null, false, 85);

        digests.runDue(now);

        Message m = messages(me.email()).get(0);
        assertThat(m.html()).contains("&lt;b&gt;Bold&lt;/b&gt; &amp; &quot;quoted&quot;").doesNotContain("<b>Bold");
    }

    @Test
    void theRunReportsWhatItDid() {
        Instant now = Instant.now();
        World w = world(now);

        Summary summary = digests.runDue(now);

        assertThat(summary.users()).isGreaterThanOrEqualTo(1);
        assertThat(summary.sent()).isGreaterThanOrEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(Map.of("sent", messages(w.me().email()).size())).containsEntry("sent", 1);
    }
}
