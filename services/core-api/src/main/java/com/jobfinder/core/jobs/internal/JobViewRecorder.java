package com.jobfinder.core.jobs.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.activation.FirstMatchesViewed;
import com.jobfinder.core.jobs.JobViews;

/**
 * Writes VIEWED for the jobs a feed page showed. One row per user and job ({@code created_at} is the latest view), so
 * the table stays as small as the set of jobs a user has seen. VIEWED is read by no ranking or search query (they all
 * name SAVED, HIDDEN or APPLIED). The first view of a UTC day publishes {@link FirstMatchesViewed} after the rows commit.
 */
@Component
class JobViewRecorder implements JobViews {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    JobViewRecorder(JdbcClient jdbc, ApplicationEventPublisher events, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void recordFeedView(UUID userId, Collection<UUID> jobIds, Instant at) {
        if (jobIds.isEmpty()) {
            return;
        }
        List<UUID> ids = List.copyOf(jobIds);
        OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        OffsetDateTime dayStart = when.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        tx.executeWithoutResult(status -> {
            jdbc.sql("select count(*) from (select pg_advisory_xact_lock(hashtextextended(:k, 0))) l")
                    .param("k", "views:" + userId).query(Long.class).single();
            boolean seenBefore = jdbc.sql("""
                    select exists (select 1 from user_job_actions where user_id = :u and action = 'VIEWED')
                    """).param("u", userId).query(Boolean.class).single();
            boolean seenToday = seenBefore && jdbc.sql("""
                    select exists (select 1 from user_job_actions where user_id = :u and action = 'VIEWED'
                                    and created_at >= :start and created_at < :end)
                    """).param("u", userId).param("start", dayStart).param("end", dayStart.plusDays(1))
                    .query(Boolean.class).single();
            int written = jdbc.sql("""
                    insert into user_job_actions (user_id, job_id, action, created_at)
                    select :u, j.id, 'VIEWED', :at from jobs j where j.id in (:ids)
                    on conflict (user_id, job_id, action) do update
                       set created_at = greatest(user_job_actions.created_at, excluded.created_at)
                    """).param("u", userId).param("at", when).param("ids", ids).update();
            if (written > 0 && !seenToday) {
                events.publishEvent(new FirstMatchesViewed(userId, at, !seenBefore));
            }
        });
    }
}
