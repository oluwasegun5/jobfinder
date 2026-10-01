package com.jobfinder.core.jobs.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.jobs.JobAction;
import com.jobfinder.core.jobs.JobCard;
import com.jobfinder.core.jobs.JobCardCompany;
import com.jobfinder.core.jobs.JobCardSalary;
import com.jobfinder.core.jobs.JobFeatures;
import com.jobfinder.core.jobs.JobFeedbackSource;
import com.jobfinder.core.jobs.JobSignal;
import com.jobfinder.core.jobs.internal.JobDtos.JobSummary;

/** The read side of the caller's job actions for the feed (docs/adr/0027-feed-and-feedback.md). Every query is scoped by user. */
@Component
class JobFeedbackQueries implements JobFeedbackSource {

    private final JdbcClient jdbc;
    private final JobSearchRepository repository;

    JobFeedbackQueries(JdbcClient jdbc, JobSearchRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobSignal> signals(UUID userId, Instant after, Instant until, int perActionLimit) {
        return jdbc.sql("""
                select job_id, company_id, title, action, created_at
                  from (select a.job_id, j.company_id, j.title, a.action, a.created_at,
                               row_number() over (partition by a.action order by a.created_at desc, a.job_id desc) as rn
                          from user_job_actions a join jobs j on j.id = a.job_id
                         where a.user_id = :userId and a.action in ('SAVED', 'HIDDEN', 'APPLIED')
                           and a.created_at > cast(:after as timestamptz) and a.created_at <= cast(:until as timestamptz)) t
                 where rn <= :limit
                 order by created_at desc, job_id desc
                """)
                .param("userId", userId).param("after", utc(after)).param("until", utc(until))
                .param("limit", perActionLimit)
                .query((rs, row) -> new JobSignal(rs.getObject("job_id", UUID.class),
                        rs.getObject("company_id", UUID.class), rs.getString("title"),
                        JobAction.valueOf(rs.getString("action")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, JobFeatures> features(UUID userId, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, JobFeatures> result = new HashMap<>();
        jdbc.sql("""
                select j.id, j.company_id, j.title,
                       exists (select 1 from user_job_actions a
                                where a.user_id = :userId and a.job_id = j.id and a.action = 'SAVED') as saved,
                       exists (select 1 from user_job_actions a
                                where a.user_id = :userId and a.job_id = j.id and a.action = 'APPLIED') as applied
                  from jobs j where j.id in (:ids)
                """)
                .param("userId", userId).param("ids", List.copyOf(jobIds))
                .query((rs, row) -> {
                    JobFeatures f = new JobFeatures(rs.getObject("id", UUID.class),
                            rs.getObject("company_id", UUID.class), rs.getString("title"), rs.getBoolean("saved"),
                            rs.getBoolean("applied"));
                    result.put(f.jobId(), f);
                    return null;
                }).list();
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, JobCard> cards(UUID userId, Collection<UUID> jobIds) {
        Map<UUID, JobCard> result = new LinkedHashMap<>();
        repository.summaries(userId, jobIds).forEach((id, s) -> result.put(id, card(s)));
        return result;
    }

    private static JobCard card(JobSummary s) {
        return new JobCard(s.id(), s.title(), new JobCardCompany(s.company().id(), s.company().name()), s.location(),
                s.city(), s.country(), s.workMode(), s.employmentType(), s.seniority(),
                s.salary() == null ? null
                        : new JobCardSalary(s.salary().min(), s.salary().max(), s.salary().currency(),
                                s.salary().period()),
                s.postedAt(), s.status(), s.summary(), s.saved(), s.applied());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
