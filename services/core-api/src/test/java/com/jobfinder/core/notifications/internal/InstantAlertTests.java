package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.notifications.internal.DeliveryService.Outcome;
import com.jobfinder.core.notifications.internal.InstantAlertService.Summary;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchCriteria;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchFrequency;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchRequest;
import com.jobfinder.core.notifications.internal.NotificationDtos.UnsubscribeScope;

/**
 * Instant alerts: only scores at or above the user's threshold, never the same job twice (nor one a digest listed),
 * at most three emails a day, nothing for users who must not be mailed, no model call, and INSTANT saved searches.
 */
class InstantAlertTests extends NotificationsTestSupport {

    @Autowired
    private UnsubscribeTokens tokens;

    private Account alertable(int threshold) {
        Account me = onboarded();
        settings(me.id(), false, "DAILY", 8, 1, null, true, threshold);
        return me;
    }

    @Test
    void onlyMatchesAtOrAboveTheThresholdAlert() {
        Account me = alertable(85);
        UUID high = strongJob("Java Backend Engineer", "Globex");
        UUID onTheLine = strongJob("Platform Engineer", "Initech");
        UUID below = strongJob("Office Manager", "Umbrella");
        score(me.id(), scores(high, 90, onTheLine, 85, below, 84));
        int modelCalls = scoreRequestCount(me.id());

        Outcome outcome = alerts.alertMatches(me.id(), Instant.now());

        assertThat(outcome).isEqualTo(Outcome.SENT);
        List<Message> sent = messages(me.email());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).subject()).isEqualTo("Job alert: 2 new strong matches on JobFinder");
        assertThat(sent.get(0).text()).contains(WEB + "/jobs/" + high, WEB + "/jobs/" + onTheLine)
                .doesNotContain(below.toString());
        assertThat(sent.get(0).header("List-Unsubscribe-Post")).isEqualTo("List-Unsubscribe=One-Click");
        assertThat(tokens.verify(sent.get(0).oneClickToken())).get()
                .satisfies(claims -> assertThat(claims.scope()).isEqualTo(UnsubscribeScope.INSTANT_ALERTS));
        // an alert is built from the score cache: it asks the model for nothing
        assertThat(scoreRequestCount(me.id())).isEqualTo(modelCalls);
    }

    @Test
    void aJobIsNeverAlertedTwiceAndTheNextAlertHoldsOnlyWhatIsNew() {
        Account me = alertable(80);
        UUID first = strongJob("Java Backend Engineer", "Globex");
        score(me.id(), scores(first, 90));

        assertThat(alerts.alertMatches(me.id(), Instant.now())).isEqualTo(Outcome.SENT);
        assertThat(alerts.alertMatches(me.id(), Instant.now())).isEqualTo(Outcome.NOT_CLAIMED);
        assertThat(messages(me.email())).hasSize(1);

        UUID second = strongJob("Platform Engineer", "Initech");
        score(me.id(), scores(first, 90, second, 88));
        assertThat(alerts.alertMatches(me.id(), Instant.now())).isEqualTo(Outcome.SENT);

        List<Message> sent = messages(me.email());
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).text()).contains(second.toString()).doesNotContain(first.toString());
    }

    @Test
    void aUserGetsAtMostThreeAlertsADayAndWhatWasHeldBackComesLater() {
        Account me = alertable(80);
        Instant now = Instant.now();
        java.util.Map<UUID, Integer> all = new java.util.LinkedHashMap<>();
        UUID held = null;
        for (int i = 0; i < 4; i++) {
            held = strongJob("Engineer number " + i, "Company " + i);
            all.put(held, 90);
            score(me.id(), all);
            Outcome outcome = alerts.alertMatches(me.id(), now);
            assertThat(outcome).isEqualTo(i < 3 ? Outcome.SENT : Outcome.NOT_CLAIMED);
        }

        assertThat(messages(me.email())).hasSize(3);

        // a day later the cap has reset; the job that was held back is alerted, and only it
        assertThat(alerts.alertMatches(me.id(), now.plus(Duration.ofHours(25)))).isEqualTo(Outcome.SENT);
        List<Message> sent = messages(me.email());
        assertThat(sent).hasSize(4);
        assertThat(sent.get(0).text()).contains(held.toString());
        assertThat(count("select count(*) from notification_log where user_id = ? and status = 'SENT'", me.id()))
                .isEqualTo(4);
    }

    @Test
    void theDigestLeavesOutWhatAnAlertAlreadyListed() {
        Account me = onboarded();
        Instant now = Instant.now();
        settings(me.id(), true, "DAILY", utcHour(now), 1, null, true, 85);
        UUID alerted = strongJob("Java Backend Engineer", "Globex");
        UUID later = strongJob("Platform Engineer", "Initech");
        score(me.id(), scores(alerted, 95, later, 75)); // only the first reaches the threshold
        assertThat(alerts.alertMatches(me.id(), now)).isEqualTo(Outcome.SENT);

        digests.runDue(now);

        List<Message> sent = messages(me.email());
        assertThat(sent).hasSize(2);
        Message digest = sent.stream().filter(m -> m.subject().startsWith("1 new strong match")).findFirst()
                .orElseThrow();
        assertThat(digest.text()).contains(later.toString()).doesNotContain(alerted.toString());
    }

    @Test
    void nobodyIsAlertedWhoAskedForNothingIsUnverifiedOrHasUnsubscribed() {
        Account off = onboarded(); // no settings at all: alerts are opt-in
        Account unverified = alertable(50);
        jdbc.update("update users set email_verified_at = null where id = ?", unverified.id());
        Account unsubscribed = alertable(50);
        jdbc.update("update notification_preferences set marketing_unsubscribed_at = now() where user_id = ?",
                unsubscribed.id());
        Account muted = alertable(50);
        jdbc.update("update notification_preferences set email_enabled = false where user_id = ?", muted.id());
        Account control = alertable(50);
        UUID job = strongJob("Java Backend Engineer", "Globex");
        for (Account a : List.of(off, unverified, unsubscribed, muted, control)) {
            score(a.id(), scores(job, 99));
        }

        for (Account a : List.of(off, unverified, unsubscribed, muted)) {
            assertThat(alerts.alertMatches(a.id(), Instant.now())).as(a.email()).isEqualTo(Outcome.NOT_CLAIMED);
            assertThat(messages(a.email())).isEmpty();
        }
        assertThat(alerts.alertMatches(control.id(), Instant.now())).isEqualTo(Outcome.SENT);
        assertThat(messages(control.email())).hasSize(1);
    }

    @Test
    void anUnsubscribedFromDigestsUserStillGetsAlertsTheyAskedFor() {
        Account me = alertable(70);
        jdbc.update("update notification_preferences set digests_unsubscribed_at = now() where user_id = ?", me.id());
        UUID job = strongJob("Java Backend Engineer", "Globex");
        score(me.id(), scores(job, 95));

        assertThat(alerts.alertMatches(me.id(), Instant.now())).isEqualTo(Outcome.SENT);
    }

    // --- saved searches set to INSTANT ---

    private SavedSearchService.Row instantSearch(Account me, String name, String q) {
        UUID id = savedSearches.create(me.id(), new SavedSearchRequest(name,
                new SavedSearchCriteria(q, null, null, null, null, null, null, null), SavedSearchFrequency.INSTANT))
                .id();
        return savedSearches.find(me.id(), id).orElseThrow();
    }

    private UUID newJob(String title) {
        return insert(spec().title(title).postedAt(Instant.now().plusMillis(50)));
    }

    @Test
    void aSearchSetToInstantAlertsOnNewJobsOnceAndTheWatermarkMoves() {
        Instant now = Instant.now().plus(Duration.ofMinutes(10));
        Account me = verified();
        SavedSearchService.Row search = instantSearch(me, "Go jobs", "golang");
        UUID hit = newJob("Golang Engineer");
        UUID miss = newJob("Java Engineer");

        Summary first = alerts.pollSearches(now);

        assertThat(first.sent()).isGreaterThanOrEqualTo(1);
        List<Message> sent = messages(me.email());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).subject()).isEqualTo("Job alert: 1 new job for \"Go jobs\"");
        assertThat(sent.get(0).text()).contains(WEB + "/jobs/" + hit).doesNotContain(miss.toString());
        assertThat(tokens.verify(sent.get(0).oneClickToken())).get().satisfies(claims -> {
            assertThat(claims.scope()).isEqualTo(UnsubscribeScope.SAVED_SEARCH);
            assertThat(claims.savedSearchId()).isEqualTo(search.id());
        });
        assertThat(savedSearches.find(me.id(), search.id()).orElseThrow().lastRunAt()).isAfter(search.lastRunAt());

        alerts.pollSearches(now.plusSeconds(60));
        assertThat(messages(me.email())).hasSize(1);

        UUID next = insert(spec().title("Golang Platform Lead").postedAt(now.plus(Duration.ofMinutes(5))));
        alerts.pollSearches(now.plus(Duration.ofMinutes(10)));
        List<Message> after = messages(me.email());
        assertThat(after).hasSize(2);
        assertThat(after.get(0).text()).contains(next.toString()).doesNotContain(hit.toString());
    }

    @Test
    void searchAlertsShareTheDailyCapAndKeepWhatTheCapHeldBack() {
        Account me = alertable(80);
        Instant realNow = Instant.now();
        java.util.Map<UUID, Integer> all = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            all.put(strongJob("Engineer number " + i, "Company " + i), 90);
            score(me.id(), all);
            assertThat(alerts.alertMatches(me.id(), realNow)).isEqualTo(Outcome.SENT);
        }
        SavedSearchService.Row search = instantSearch(me, "Held", "zig");
        UUID zig = newJob("Zig Engineer");
        Instant now = realNow.plus(Duration.ofMinutes(10));

        Summary limited = alerts.pollSearches(now);

        assertThat(limited.rateLimited()).isGreaterThanOrEqualTo(1);
        assertThat(messages(me.email())).hasSize(3);
        assertThat(savedSearches.find(me.id(), search.id()).orElseThrow().lastRunAt()).isEqualTo(search.lastRunAt());

        alerts.pollSearches(realNow.plus(Duration.ofHours(25)));
        List<Message> sent = messages(me.email());
        assertThat(sent).hasSize(4);
        assertThat(sent.get(0).text()).contains(zig.toString());
    }

    @Test
    void aSearchOfAUserWhoCannotBeMailedDropsTheBacklogSoTurningMailOnDoesNotFlood() {
        Instant now = Instant.now().plus(Duration.ofMinutes(10));
        Account me = verified();
        SavedSearchService.Row search = instantSearch(me, "Quiet", "elixir");
        jdbc.update("update users set email_verified_at = null where id = ?", me.id());
        newJob("Elixir Engineer");

        alerts.pollSearches(now);

        assertThat(messages(me.email())).isEmpty();
        assertThat(savedSearches.find(me.id(), search.id()).orElseThrow().lastRunAt()).isAfter(search.lastRunAt());
        jdbc.update("update users set email_verified_at = now() where id = ?", me.id());
        alerts.pollSearches(now.plusSeconds(60));
        assertThat(messages(me.email())).isEmpty();
    }
}
