package com.jobfinder.core.jobs.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.jobs.JobForMatching;
import com.jobfinder.core.jobs.JobMatchSource;
import com.jobfinder.core.jobs.JobSelection;
import com.jobfinder.core.jobs.RecalledJob;

/**
 * The stage-1 filters and the stage-2 recall of the matching engine as one query (docs/adr/0026-matching-engine.md):
 * the filters and the user's hidden jobs are applied inside the HNSW scan with pgvector's iterative scan, the way
 * "similar jobs" does (ADR 0023), so a selective filter still yields up to {@code limit} jobs.
 */
@Repository
class JobMatchRepository implements JobMatchSource {

    /** A recall that takes longer than this is cancelled (the matching run fails for that user, not the batch). */
    private static final String RECALL_TIMEOUT_MS = "15000";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    JobMatchRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public List<RecalledJob> recall(String model, float[] vector, JobSelection s, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("vec", VectorLiteral.of(vector));
        params.put("model", model);
        params.put("userId", s.userId());
        params.put("limit", limit);
        StringBuilder sql = new StringBuilder("""
                select j.id, (j.embedding <=> cast(:vec as vector))::float8 as dist, j.skills, j.sort_at
                  from jobs j join companies c on c.id = j.company_id
                 where j.status = 'ACTIVE' and (j.expires_at is null or j.expires_at > now())
                   and j.embedding is not null and j.embedding_model = :model
                   and not exists (select 1 from user_job_actions h
                                    where h.user_id = :userId and h.job_id = j.id and h.action = 'HIDDEN')
                """);
        if (!s.workModes().isEmpty()) {
            sql.append(" and (j.work_mode is null or j.work_mode in (:workModes))\n");
            params.put("workModes", s.workModes());
        }
        if (!s.locationTerms().isEmpty() || !s.countryCodes().isEmpty()) {
            // Remote jobs are open to anyone; a job that says nothing about where it is is not ruled out.
            sql.append("""
                       and (j.work_mode = 'REMOTE'
                            or (j.city is null and j.country is null and j.location_raw is null)
                            or lower(j.city) = any (cast(:terms as text[]))
                            or upper(j.country) = any (cast(:codes as text[]))
                            or exists (select 1 from unnest(cast(:terms as text[])) as t(term)
                                        where strpos(lower(j.location_raw), t.term) > 0))
                    """);
            params.put("terms", s.locationTerms().toArray(String[]::new));
            params.put("codes", s.countryCodes().toArray(String[]::new));
        }
        if (s.minAnnualSalary() != null && s.salaryCurrency() != null) {
            // Only a stated yearly figure in the floor's own currency can rule a job out.
            sql.append("""
                       and (j.salary_annual_top is null or j.salary_currency is null
                            or upper(j.salary_currency) <> :salaryCurrency or j.salary_annual_top >= :minSalary)
                    """);
            params.put("salaryCurrency", s.salaryCurrency());
            params.put("minSalary", s.minAnnualSalary());
        }
        if (!s.seniorities().isEmpty()) {
            sql.append(" and (j.seniority is null or j.seniority in (:seniorities))\n");
            params.put("seniorities", s.seniorities());
        }
        if (!s.excludedCompanies().isEmpty()) {
            sql.append("""
                       and lower(c.name) <> all (cast(:excludedCompanies as text[]))
                       and lower(c.normalized_name) <> all (cast(:excludedCompanies as text[]))
                    """);
            params.put("excludedCompanies", s.excludedCompanies().toArray(String[]::new));
        }
        if (!s.excludedIndustries().isEmpty()) {
            sql.append(" and (c.industry is null or lower(c.industry) <> all (cast(:excludedIndustries as text[])))\n");
            params.put("excludedIndustries", s.excludedIndustries().toArray(String[]::new));
        }
        sql.append(" order by j.embedding <=> cast(:vec as vector), j.id limit :limit");
        String inner = sql.toString();
        int ef = Math.min(1000, Math.max(100, limit));
        List<RecalledJob> rows = tx.execute(status -> {
            jdbc.sql("""
                    select set_config('hnsw.ef_search', :ef, true), set_config('hnsw.iterative_scan', 'relaxed_order', true),
                           set_config('statement_timeout', :timeout, true)
                    """).param("ef", Integer.toString(ef)).param("timeout", RECALL_TIMEOUT_MS).query().listOfRows();
            // Rows come back approximately ordered from the iterative scan: sort exactly, with the id as tie-break.
            return jdbc.sql("select * from (" + inner + ") t order by t.dist, t.id").params(params)
                    .query((rs, row) -> new RecalledJob(rs.getObject("id", UUID.class), 1.0 - rs.getDouble("dist"),
                            texts(rs.getArray("skills")), rs.getTimestamp("sort_at").toInstant()))
                    .list();
        });
        return rows == null ? List.of() : rows;
    }

    @Override
    public List<JobForMatching> jobs(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select j.id, j.title, c.name as company, j.city, j.country, j.location_raw, j.work_mode,
                       j.employment_type, j.seniority, j.salary_min, j.salary_max, j.salary_currency,
                       j.salary_period, j.skills, j.description_text, j.status, j.expires_at, j.sort_at
                  from jobs j join companies c on c.id = j.company_id
                 where j.id in (:ids)
                """).param("ids", ids)
                .query((rs, row) -> {
                    Instant expiresAt = rs.getTimestamp("expires_at") == null ? null
                            : rs.getTimestamp("expires_at").toInstant();
                    boolean active = "ACTIVE".equals(rs.getString("status"))
                            && (expiresAt == null || expiresAt.isAfter(Instant.now()));
                    return new JobForMatching(rs.getObject("id", UUID.class), rs.getString("title"),
                            rs.getString("company"), rs.getString("city"), rs.getString("country"),
                            rs.getString("location_raw"), rs.getString("work_mode"),
                            rs.getString("employment_type"), rs.getString("seniority"),
                            rs.getBigDecimal("salary_min"), rs.getBigDecimal("salary_max"),
                            rs.getString("salary_currency"), rs.getString("salary_period"),
                            texts(rs.getArray("skills")), rs.getString("description_text"), active,
                            rs.getTimestamp("sort_at").toInstant());
                }).list();
    }

    @Override
    public Optional<Double> similarity(UUID jobId, String model, float[] vector) {
        return jdbc.sql("""
                select 1.0 - (embedding <=> cast(:vec as vector))::float8 from jobs
                 where id = :id and embedding is not null and embedding_model = :model
                """).param("vec", VectorLiteral.of(vector)).param("id", jobId).param("model", model)
                .query(Double.class).optional();
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
