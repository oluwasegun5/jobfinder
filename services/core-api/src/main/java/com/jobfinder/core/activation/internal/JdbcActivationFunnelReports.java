package com.jobfinder.core.activation.internal;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.activation.ActivationFunnelReports;
import com.jobfinder.core.activation.FunnelReport;
import com.jobfinder.core.activation.FunnelReport.StepCount;
import com.jobfinder.core.activation.FunnelReport.WeekCohort;
import com.jobfinder.core.activation.FunnelStep;

/**
 * The funnel, derived from the tables that already record each step (docs/adr/0045-activation-funnel.md): one
 * read-only query, no table of its own. A user counts at a step only if they reached every step before it.
 */
@Component
class JdbcActivationFunnelReports implements ActivationFunnelReports {

    private static final int STEPS = FunnelStep.values().length;

    private final JdbcClient jdbc;

    JdbcActivationFunnelReports(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public FunnelReport report(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to.plusDays(1))) {
            throw new IllegalArgumentException("A range may cover at most " + MAX_RANGE_DAYS + " days");
        }
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        // Per signup week, how many users reached each step, strictly: s3 counts only users who also have s2, and so on.
        List<WeekCohort> cohorts = jdbc.sql("""
                with cohort as (
                    select u.id,
                           (date_trunc('week', u.created_at at time zone 'UTC'))::date as week,
                           (u.email_verified_at is not null) as verified,
                           exists (select 1 from resumes r where r.user_id = u.id) as uploaded,
                           exists (select 1 from resumes r where r.user_id = u.id and r.parse_status = 'PARSED') as parsed,
                           exists (select 1 from preferences p where p.user_id = u.id) as onboarded,
                           exists (select 1 from user_job_actions a where a.user_id = u.id and a.action = 'VIEWED') as viewed,
                           exists (select 1 from generated_documents d where d.user_id = u.id
                                    and d.type = 'TAILORED_RESUME' and d.status <> 'GENERATING') as tailored
                    from users u
                    where u.created_at >= :start and u.created_at < :end and u.role <> 'ADMIN' and u.deleted_at is null
                )
                select week,
                       count(*) as c1,
                       count(*) filter (where verified) as c2,
                       count(*) filter (where verified and uploaded) as c3,
                       count(*) filter (where verified and uploaded and parsed) as c4,
                       count(*) filter (where verified and uploaded and parsed and onboarded) as c5,
                       count(*) filter (where verified and uploaded and parsed and onboarded and viewed) as c6,
                       count(*) filter (where verified and uploaded and parsed and onboarded and viewed and tailored) as c7
                from cohort group by week order by week
                """)
                .param("start", start).param("end", end)
                .query((rs, row) -> {
                    List<Long> counts = new ArrayList<>(STEPS);
                    for (int i = 1; i <= STEPS; i++) {
                        counts.add(rs.getLong("c" + i));
                    }
                    Date week = rs.getDate("week");
                    return new WeekCohort(week.toLocalDate(), List.copyOf(counts));
                }).list();

        long[] totals = new long[STEPS];
        for (WeekCohort cohort : cohorts) {
            for (int i = 0; i < STEPS; i++) {
                totals[i] += cohort.counts().get(i);
            }
        }
        List<StepCount> steps = new ArrayList<>(STEPS);
        for (int i = 0; i < STEPS; i++) {
            Double fromPrevious = i == 0 ? null : ratio(totals[i], totals[i - 1]);
            steps.add(new StepCount(FunnelStep.values()[i], totals[i], fromPrevious, ratio(totals[i], totals[0])));
        }
        return new FunnelReport(from, to, List.copyOf(steps), List.copyOf(cohorts));
    }

    /** {@code part / whole} rounded to four decimals; null when there is no whole to compare with. */
    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 10_000.0 / whole) / 10_000.0;
    }
}
