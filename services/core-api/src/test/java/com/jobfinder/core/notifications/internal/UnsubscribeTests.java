package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.jobfinder.core.notifications.internal.NotificationDtos.UnsubscribeScope;

/**
 * The public unsubscribe endpoints: GET describes, POST does it, no login, one answer for every bad token, repeats and
 * deleted accounts harmless, and mail the user needs (password reset) never affected.
 */
class UnsubscribeTests extends NotificationsTestSupport {

    @Autowired
    private UnsubscribeTokens tokens;

    private ResultActions describe(String token) throws Exception {
        return mvc.perform(get("/notifications/unsubscribe/" + token));
    }

    private ResultActions perform(String token) throws Exception {
        return mvc.perform(post("/notifications/unsubscribe/" + token));
    }

    private boolean flag(UUID user, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select " + column + " is not null from notification_preferences where user_id = ?", Boolean.class,
                user));
    }

    private Account withSettings() {
        Account a = onboarded();
        settings(a.id(), true, "DAILY", 8, 1, null, true, 85);
        return a;
    }

    @Test
    void getOnlyDescribesAndPostDoesIt() throws Exception {
        Account me = withSettings();
        String token = tokens.issue(me.id(), UnsubscribeScope.DIGESTS, null);

        describe(token).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("DIGESTS"));
        assertThat(flag(me.id(), "digests_unsubscribed_at")).isFalse();

        perform(token).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("DIGESTS"));
        assertThat(flag(me.id(), "digests_unsubscribed_at")).isTrue();
        assertThat(flag(me.id(), "marketing_unsubscribed_at")).isFalse();
    }

    @Test
    void theEndpointsNeedNoLoginAndNoCsrfToken() throws Exception {
        String token = tokens.issue(UUID.randomUUID(), UnsubscribeScope.DIGESTS, null);
        // not 401 or 403: reachable without credentials (an unknown account is still a valid, harmless request)
        describe(token).andExpect(status().isOk());
        perform(token).andExpect(status().isOk());
        // only GET and POST are public
        mvc.perform(put("/notifications/unsubscribe/" + token)).andExpect(status().is4xxClientError());
        mvc.perform(delete("/notifications/unsubscribe/" + token)).andExpect(status().is4xxClientError());
    }

    @Test
    void eachScopeSwitchesOffExactlyItsOwnMail() throws Exception {
        Account digests = withSettings();
        perform(tokens.issue(digests.id(), UnsubscribeScope.DIGESTS, null)).andExpect(status().isOk());
        assertThat(flag(digests.id(), "digests_unsubscribed_at")).isTrue();
        assertThat(flag(digests.id(), "marketing_unsubscribed_at")).isFalse();

        Account instant = withSettings();
        UUID instantSearch = searchOf(instant, "INSTANT");
        UUID dailySearch = searchOf(instant, "DAILY");
        perform(tokens.issue(instant.id(), UnsubscribeScope.INSTANT_ALERTS, null)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select instant_enabled from notification_preferences where user_id = ?",
                Boolean.class, instant.id())).isFalse();
        assertThat(frequency(instantSearch)).isEqualTo("OFF");
        assertThat(frequency(dailySearch)).isEqualTo("DAILY");
        assertThat(jdbc.queryForObject("select digest_enabled from notification_preferences where user_id = ?",
                Boolean.class, instant.id())).isTrue();

        Account everything = withSettings();
        UUID search = searchOf(everything, "INSTANT");
        perform(tokens.issue(everything.id(), UnsubscribeScope.MARKETING, null)).andExpect(status().isOk());
        assertThat(flag(everything.id(), "marketing_unsubscribed_at")).isTrue();
        assertThat(frequency(search)).isEqualTo("OFF");

        Account oneSearch = withSettings();
        UUID mine = searchOf(oneSearch, "DAILY");
        UUID alsoMine = searchOf(oneSearch, "DAILY");
        perform(tokens.issue(oneSearch.id(), UnsubscribeScope.SAVED_SEARCH, mine)).andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSearchName").value("search DAILY"));
        assertThat(frequency(mine)).isEqualTo("OFF");
        assertThat(frequency(alsoMine)).isEqualTo("DAILY");
        assertThat(flag(oneSearch.id(), "digests_unsubscribed_at")).isFalse();
    }

    @Test
    void aTokenCannotReachAnotherUsersSearch() throws Exception {
        Account victim = withSettings();
        UUID victimSearch = searchOf(victim, "DAILY");
        Account attacker = withSettings();

        perform(tokens.issue(attacker.id(), UnsubscribeScope.SAVED_SEARCH, victimSearch)).andExpect(status().isOk());

        assertThat(frequency(victimSearch)).isEqualTo("DAILY");
    }

    @Test
    void repeatingItChangesNothingFurther() throws Exception {
        Account me = withSettings();
        String token = tokens.issue(me.id(), UnsubscribeScope.MARKETING, null);
        perform(token).andExpect(status().isOk());
        Instant first = jdbc.queryForObject(
                "select marketing_unsubscribed_at from notification_preferences where user_id = ?",
                java.sql.Timestamp.class, me.id()).toInstant();

        perform(token).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("MARKETING"));

        assertThat(jdbc.queryForObject("select marketing_unsubscribed_at from notification_preferences where user_id = ?",
                java.sql.Timestamp.class, me.id()).toInstant()).isEqualTo(first);
    }

    @Test
    void anUnsubscribeWorksForAUserWhoNeverSavedSettings() throws Exception {
        Account me = verified();

        perform(tokens.issue(me.id(), UnsubscribeScope.MARKETING, null)).andExpect(status().isOk());

        assertThat(flag(me.id(), "marketing_unsubscribed_at")).isTrue();
    }

    @Test
    void everyKindOfBadTokenGetsTheSameAnswer() throws Exception {
        String good = tokens.issue(UUID.randomUUID(), UnsubscribeScope.DIGESTS, null);
        String other = tokens.issue(UUID.randomUUID(), UnsubscribeScope.MARKETING, null);
        String payload = good.substring(0, good.indexOf('.'));
        String mac = good.substring(good.indexOf('.') + 1);
        String[] bad = { "garbage", "a.b", "." + mac, payload + ".", payload + "." + mac + "x",
                other.substring(0, other.indexOf('.')) + "." + mac, "x".repeat(500), payload + "." + "A".repeat(43) };

        String reference = null;
        for (String token : bad) {
            String answer = describe(token).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("invalid_unsubscribe_link")).andReturn().getResponse()
                    .getContentAsString().replaceAll("\"instance\":\"[^\"]*\",", "");
            perform(token).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("invalid_unsubscribe_link"));
            reference = reference == null ? answer : reference;
            assertThat(answer).isEqualTo(reference);
        }
    }

    @Test
    void aTokenForADeletedAccountIsAnsweredLikeAnyOtherAndStoresNothing() throws Exception {
        UUID gone = UUID.randomUUID();
        String token = tokens.issue(gone, UnsubscribeScope.MARKETING, null);

        perform(token).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("MARKETING"));
        assertThat(count("select count(*) from notification_preferences where user_id = ?", gone)).isZero();
    }

    @Test
    void passwordResetMailStillArrivesAfterEveryOptionalMailWasSwitchedOff() throws Exception {
        String email = registerVerifiedUser();
        UUID id = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        perform(tokens.issue(id, UnsubscribeScope.MARKETING, null)).andExpect(status().isOk());
        jdbc.update("update notification_preferences set email_enabled = false where user_id = ?", id);
        int before = mailsTo(email).size();

        postJson("/auth/forgot-password", "{\"email\":\"%s\"}".formatted(email), newIp())
                .andExpect(status().isAccepted());

        assertThat(awaitMails(email, before + 1)).hasSizeGreaterThan(before);
    }

    @Test
    void theLinkInARealDigestEmailUnsubscribesAndTheNextDigestIsNotSent() throws Exception {
        Instant now = Instant.now();
        Account me = onboarded();
        dailyDigestAt(me.id(), now);
        UUID job = strongJob("Java Backend Engineer", "Globex");
        score(me.id(), scores(job, 92));
        digests.runDue(now);
        Message digest = messages(me.email()).get(0);

        // one click from the mail client (RFC 8058) and the page behind the footer link agree
        String token = digest.oneClickToken();
        assertThat(digest.header("List-Unsubscribe-Post")).isEqualTo("List-Unsubscribe=One-Click");
        assertThat(digest.pageTokens()).contains(token);
        describe(token).andExpect(jsonPath("$.scope").value("DIGESTS"));
        perform(token).andExpect(status().isOk());

        UUID next = strongJob("Platform Engineer", "Initech");
        score(me.id(), scores(job, 92, next, 90));
        digests.runDue(now.plus(java.time.Duration.ofDays(1)));

        assertThat(messages(me.email())).hasSize(1);
    }

    private UUID searchOf(Account a, String frequency) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into saved_searches (id, user_id, name, q, frequency, last_run_at, created_at, updated_at) "
                + "values (?, ?, ?, 'java', ?, now(), now(), now())", id, a.id(), "search " + frequency, frequency);
        return id;
    }

    private String frequency(UUID search) {
        return jdbc.queryForObject("select frequency from saved_searches where id = ?", String.class, search);
    }
}
