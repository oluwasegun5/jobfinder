package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Component;

/**
 * Writes {@code jobs} and {@code job_sources}. A job is one row per fingerprint; a listing is one
 * {@code job_sources} row per (source, external id). Everything here runs inside the caller's
 * transaction.
 */
@Component
class JobStore {

    /** A listing and whether it is its job's first one (the "owner" whose later refreshes overwrite the job). */
    record Link(UUID id, UUID jobId, boolean owner) {
    }

    private final JdbcClient jdbc;

    JobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Link> findLink(UUID sourceId, String externalId) {
        return jdbc.sql("""
                select js.id, js.job_id,
                       not exists (select 1 from job_sources o
                                    where o.job_id = js.job_id and o.id <> js.id
                                      and (o.created_at < js.created_at
                                           or (o.created_at = js.created_at and o.id < js.id))) as owner
                  from job_sources js
                 where js.source_id = :sourceId and js.external_id = :externalId
                """)
                .param("sourceId", sourceId)
                .param("externalId", externalId)
                .query((rs, row) -> new Link(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                        rs.getBoolean("owner")))
                .optional();
    }

    Optional<UUID> findByFingerprint(String fingerprint) {
        return jdbc.sql("select id from jobs where fingerprint = :fingerprint")
                .param("fingerprint", fingerprint)
                .query(UUID.class)
                .optional();
    }

    int countLinks(UUID jobId) {
        return jdbc.sql("select count(*) from job_sources where job_id = :jobId")
                .param("jobId", jobId)
                .query(Integer.class)
                .single();
    }

    /** Inserts a new job; false if another job already has the fingerprint (a concurrent run got there first). */
    boolean insert(UUID id, UUID companyId, NormalizedJob job, Instant now) {
        StatementSpec statement = jdbc.sql("""
                insert into jobs (id, company_id, title, normalized_title, description_html, description_text,
                                  location_raw, city, country, work_mode, employment_type, seniority, salary_min,
                                  salary_max, salary_currency, salary_period, apply_url, apply_channel, posted_at,
                                  expires_at, status, fingerprint, created_at, updated_at)
                values (:id, :companyId, :title, :normalizedTitle, :descriptionHtml, :descriptionText,
                        :locationRaw, :city, :country, :workMode, :employmentType, :seniority, :salaryMin,
                        :salaryMax, :salaryCurrency, :salaryPeriod, :applyUrl, 'EXTERNAL', :postedAt,
                        :expiresAt, :status, :fingerprint, :now, :now)
                on conflict (fingerprint) do nothing
                """)
                .param("id", id)
                .param("companyId", companyId)
                .param("status", initialStatus(job, now));
        return content(statement, job, now).update() == 1;
    }

    /** The job's first listing refreshed it: replace its content, reactivate it, and take the new fingerprint. */
    void overwrite(UUID jobId, UUID companyId, NormalizedJob job, Instant now) {
        content(jdbc.sql("""
                update jobs
                   set company_id = :companyId, title = :title, normalized_title = :normalizedTitle,
                       description_html = :descriptionHtml, description_text = :descriptionText,
                       location_raw = :locationRaw, city = :city, country = :country, work_mode = :workMode,
                       employment_type = :employmentType, seniority = :seniority, salary_min = :salaryMin,
                       salary_max = :salaryMax, salary_currency = :salaryCurrency, salary_period = :salaryPeriod,
                       apply_url = :applyUrl, posted_at = :postedAt, expires_at = :expiresAt,
                       status = :status, fingerprint = :fingerprint, updated_at = :now
                 where id = :id
                """)
                .param("id", jobId)
                .param("companyId", companyId)
                .param("status", initialStatus(job, now)), job, now).update();
    }

    /**
     * Another listing of the job, or a later refresh by one that is not the owner: fill what the job
     * lacks, never replace what it has, and reactivate it (someone is listing it now).
     */
    void fill(UUID jobId, NormalizedJob job, Instant now) {
        content(jdbc.sql("""
                update jobs
                   set description_html = coalesce(description_html, :descriptionHtml),
                       description_text = coalesce(description_text, :descriptionText),
                       location_raw = coalesce(location_raw, :locationRaw),
                       city = coalesce(city, :city), country = coalesce(country, :country),
                       work_mode = coalesce(work_mode, :workMode),
                       employment_type = coalesce(employment_type, :employmentType),
                       seniority = coalesce(seniority, :seniority),
                       salary_min = case when salary_currency is null and salary_min is null and salary_max is null
                                         then :salaryMin else salary_min end,
                       salary_max = case when salary_currency is null and salary_min is null and salary_max is null
                                         then :salaryMax else salary_max end,
                       salary_period = case when salary_currency is null and salary_min is null
                                                 and salary_max is null then :salaryPeriod else salary_period end,
                       salary_currency = coalesce(salary_currency, :salaryCurrency),
                       apply_url = coalesce(apply_url, :applyUrl), posted_at = coalesce(posted_at, :postedAt),
                       expires_at = coalesce(expires_at, :expiresAt),
                       status = case when coalesce(expires_at, :expiresAt) is not null
                                          and coalesce(expires_at, :expiresAt) <= :now then 'EXPIRED' else 'ACTIVE' end,
                       updated_at = :now
                 where id = :id
                """)
                .param("id", jobId), job, now).update();
    }

    void expireJob(UUID jobId, Instant now) {
        jdbc.sql("update jobs set status = 'EXPIRED', updated_at = :now where id = :id and status <> 'EXPIRED'")
                .param("id", jobId)
                .param("now", utc(now))
                .update();
    }

    /** Records that this source listed the job now: a new listing, or a refreshed or moved one. */
    void upsertLink(UUID jobId, UUID sourceId, UUID targetId, String externalId, String url, Instant now) {
        jdbc.sql("""
                insert into job_sources (id, job_id, source_id, target_id, external_id, url, last_seen_at,
                                         missed_runs, created_at, updated_at)
                values (:id, :jobId, :sourceId, :targetId, :externalId, :url, :now, 0, :now, :now)
                on conflict (source_id, external_id) do update
                   set job_id = excluded.job_id, target_id = excluded.target_id, url = excluded.url,
                       last_seen_at = excluded.last_seen_at, missed_runs = 0, updated_at = excluded.updated_at
                """)
                .param("id", UUID.randomUUID())
                .param("jobId", jobId)
                .param("sourceId", sourceId)
                .param("targetId", targetId)
                .param("externalId", externalId)
                .param("url", url)
                .param("now", utc(now))
                .update();
    }

    /**
     * After a target was fetched successfully by a full-listing source: every listing of that target
     * not seen since the run began has been missed once more.
     */
    int markMissed(UUID sourceId, UUID targetId, Instant runStartedAt) {
        return jdbc.sql("""
                update job_sources set missed_runs = missed_runs + 1, updated_at = now()
                 where source_id = :sourceId and target_id = :targetId and last_seen_at < :runStartedAt
                """)
                .param("sourceId", sourceId)
                .param("targetId", targetId)
                .param("runStartedAt", utc(runStartedAt))
                .update();
    }

    /**
     * Expires the active jobs listed by this source that have no live listing left, or whose own
     * expiry date has passed. A listing is live while a full-listing source (not an aggregator) has
     * missed it fewer than {@code maxMissedRuns} times, or while an aggregator last saw it no earlier
     * than {@code staleCutoff} (PLAN.md section 6). Returns how many jobs expired.
     */
    int expire(UUID sourceId, Instant now, int maxMissedRuns, Instant staleCutoff) {
        return jdbc.sql("""
                update jobs j set status = 'EXPIRED', updated_at = :now
                 where j.status = 'ACTIVE'
                   and exists (select 1 from job_sources t where t.job_id = j.id and t.source_id = :sourceId)
                   and ((j.expires_at is not null and j.expires_at <= :now)
                        or not exists (select 1 from job_sources l join sources s on s.id = l.source_id
                                        where l.job_id = j.id
                                          and ((s.kind <> 'AGGREGATOR' and l.missed_runs < :maxMissedRuns)
                                               or (s.kind = 'AGGREGATOR' and l.last_seen_at >= :staleCutoff))))
                """)
                .param("now", utc(now))
                .param("sourceId", sourceId)
                .param("maxMissedRuns", maxMissedRuns)
                .param("staleCutoff", utc(staleCutoff))
                .update();
    }

    private static StatementSpec content(StatementSpec statement, NormalizedJob job, Instant now) {
        return statement
                .param("title", job.title())
                .param("normalizedTitle", job.normalizedTitle())
                .param("descriptionHtml", job.descriptionHtml(), java.sql.Types.VARCHAR)
                .param("descriptionText", job.descriptionText(), java.sql.Types.VARCHAR)
                .param("locationRaw", job.locationRaw(), java.sql.Types.VARCHAR)
                .param("city", job.city(), java.sql.Types.VARCHAR)
                .param("country", job.country(), java.sql.Types.VARCHAR)
                .param("workMode", job.workMode() == null ? null : job.workMode().name(), java.sql.Types.VARCHAR)
                .param("employmentType", job.employmentType() == null ? null : job.employmentType().name(),
                        java.sql.Types.VARCHAR)
                .param("seniority", job.seniority() == null ? null : job.seniority().name(), java.sql.Types.VARCHAR)
                .param("salaryMin", job.salaryMin(), java.sql.Types.NUMERIC)
                .param("salaryMax", job.salaryMax(), java.sql.Types.NUMERIC)
                .param("salaryCurrency", job.salaryCurrency(), java.sql.Types.VARCHAR)
                .param("salaryPeriod", job.salaryPeriod() == null ? null : job.salaryPeriod().name(),
                        java.sql.Types.VARCHAR)
                .param("applyUrl", job.applyUrl(), java.sql.Types.VARCHAR)
                .param("postedAt", job.postedAt() == null ? null : utc(job.postedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expiresAt", job.expiresAt() == null ? null : utc(job.expiresAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("fingerprint", job.fingerprint())
                .param("now", utc(now));
    }

    /** A job whose own expiry date has already passed is stored as expired, not briefly active. */
    private static String initialStatus(NormalizedJob job, Instant now) {
        return job.expiresAt() != null && !job.expiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE";
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
