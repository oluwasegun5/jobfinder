package com.jobfinder.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * With instant alerts switched on, a ranking run that scores jobs is what triggers the alert: the listener is wired to the
 * matching event, runs off the caller's thread, and cannot break the ranking.
 */
@TestPropertySource(properties = { "app.notifications.instant.enabled=true",
        "app.notifications.instant.initial-delay=1h" })
class InstantAlertWiringTests extends NotificationsTestSupport {

    @MockitoSpyBean
    private InstantAlertService spy;

    @Test
    void aRankingRunThatScoresAStrongJobSendsTheAlert() {
        Account me = onboarded();
        settings(me.id(), false, "DAILY", 8, 1, null, true, 85);
        UUID job = strongJob("Java Backend Engineer", "Globex");

        score(me.id(), scores(job, 93)); // the real pipeline publishes the event after it scored

        Message alert = awaitMessages(me.email(), 1).get(0);
        assertThat(alert.subject()).isEqualTo("Job alert: 1 new strong match on JobFinder");
        assertThat(alert.text()).contains("http://localhost:3000/jobs/" + job);
    }

    @Test
    void aRunThatScoredNothingNewSendsNothingMore() {
        Account me = onboarded();
        settings(me.id(), false, "DAILY", 8, 1, null, true, 85);
        UUID job = strongJob("Java Backend Engineer", "Globex");
        score(me.id(), scores(job, 93));
        awaitMessages(me.email(), 1);

        matchService.rankedMatches(me.id()); // everything is cached: no model call, no event

        assertThat(messages(me.email())).hasSize(1);
    }

    @Test
    void aFailingAlertNeverFailsTheRankingThatTriggeredIt() {
        Account me = onboarded();
        settings(me.id(), false, "DAILY", 8, 1, null, true, 85);
        UUID job = strongJob("Java Backend Engineer", "Globex");
        doThrow(new IllegalStateException("boom")).when(spy).alertMatches(eq(me.id()), any(Instant.class));

        assertThatCode(() -> score(me.id(), scores(job, 93))).doesNotThrowAnyException();

        verify(spy, timeout(5000)).alertMatches(eq(me.id()), any(Instant.class));
        assertThat(messages(me.email())).isEmpty();
    }
}
