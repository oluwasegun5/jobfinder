package com.jobfinder.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.jobfinder.core.activation.FirstMatchesViewed;

/**
 * Milestone M4: the feed's first page records VIEWED for the jobs it showed (allowed since V20), and the first view of
 * each UTC day publishes {@link FirstMatchesViewed}.
 */
@RecordApplicationEvents
class JobViewsTests extends JobsTestSupport {

    @Autowired
    private JobViews views;

    @Autowired
    private ApplicationEvents events;

    @Test
    void theFirstViewOfEachUtcDayIsAMilestoneAndEveryViewIsRecorded() throws Exception {
        UUID user = userIdOf(newSession());
        UUID a = insert("Backend Engineer");
        UUID b = insert("Platform Engineer");
        Instant day1 = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(3, ChronoUnit.DAYS).plusSeconds(3600);

        views.recordFeedView(user, List.of(a, b), day1);
        views.recordFeedView(user, List.of(a), day1.plusSeconds(60));
        views.recordFeedView(user, List.of(b), day1.plus(1, ChronoUnit.DAYS));

        assertThat(count("select count(*) from user_job_actions where user_id = ? and action = 'VIEWED'", user))
                .isEqualTo(2);
        List<FirstMatchesViewed> published = events.stream(FirstMatchesViewed.class).toList();
        assertThat(published).extracting(FirstMatchesViewed::firstEver).containsExactly(true, false);
        assertThat(published).extracting(FirstMatchesViewed::at)
                .containsExactly(day1, day1.plus(1, ChronoUnit.DAYS));
    }

    @Test
    void viewingNothingIsNotAMilestone() throws Exception {
        UUID user = userIdOf(newSession());

        views.recordFeedView(user, List.of(), Instant.now());

        assertThat(events.stream(FirstMatchesViewed.class).toList()).isEmpty();
    }

    @Test
    void aViewedJobIsNeitherSavedNorHidden() throws Exception {
        Session session = newSession();
        UUID user = userIdOf(session);
        UUID job = insert("Data Engineer");

        views.recordFeedView(user, List.of(job), Instant.now());

        assertThat(ids(getAs(session, "/jobs?q=Data"))).contains(job.toString());
        assertThat(ids(getAs(session, "/saved-jobs"))).isEmpty();
    }
}
