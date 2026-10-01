package com.jobfinder.core.jobs.internal;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.jobs.internal.JobDtos.CompanyRef;
import com.jobfinder.core.jobs.internal.JobDtos.Salary;
import com.jobfinder.core.shared.ApiException;

/**
 * Every read of the job tables the search, detail and saved-list endpoints need, and the writes to
 * {@code user_job_actions}. Jobs, companies and listings are written by ingestion only; nothing here updates them.
 *
 * <p>A page is found in two steps: a narrow query yields the ids and sort keys of the page (keyset: after the
 * cursor's (key, id), never OFFSET), then one query loads the display columns of those ids.
 */
@Repository
class JobSearchRepository {

    /** One row of a page's first step: the job and its position (score, timestamp or distance, as text). */
    record Hit(UUID id, String key, double similarity) {
    }

    /** A cursor position to continue after. */
    record After(String key, UUID id) {
    }

    /** What the similar-jobs query needs to know about its source job. */
    record SimilarSource(boolean embedded, String embeddingModel) {
    }

    /**
     * Tier A (a keyword that matches the title, company or skills): relevance blended with age. The text part is
     * ts_rank_cd over the search head (V20) normalised into 0..1, plus 0.1 so every head match scores at least 0.05
     * whatever its age; the age part scales it by 1 for a job posted now down towards 0.5 for an old one (half the
     * difference gone every 30 days). Recency decides between jobs of similar relevance and never outweighs a
     * title match.
     */
    private static final String SCORE = """
            (0.1 + ts_rank_cd('{0.1,0.2,0.4,1.0}'::float4[], j.search_head, q.tsq, 32))
              * (0.5 + 0.5 * power(0.5::float8, greatest(extract(epoch from (cast(:asOf as timestamptz) - j.sort_at))::float8, 0::float8)
                                                / 2592000.0::float8))""";

    /** Keys of a keyword page's two tiers: ranked head matches, then description-only matches by recency. */
    static final String RANKED = "A:";
    static final String DESCRIPTION_ONLY = "B:";

    private final JdbcClient jdbc;
    private final SearchProperties properties;

    JobSearchRepository(JdbcClient jdbc, SearchProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    // --- search ---

    /**
     * Keyword search in two tiers. Tier A: jobs whose title, company or skills match (and the whole document does
     * too, which matters for negations), ranked by {@link #SCORE}. Tier B: jobs that match only in the description,
     * newest first and not ranked, which lets the database stop after one page instead of scoring every match of a
     * word that is in almost every description. A is always listed before B. Needs a transaction (statement timeout).
     */
    List<Hit> keyword(UUID userId, String query, JobFilters filters, Instant asOf, After after, int limit) {
        limitTime();
        boolean inTierB = after != null && after.key().startsWith(DESCRIPTION_ONLY);
        List<Hit> hits = new ArrayList<>();
        if (!inTierB) {
            hits.addAll(rankedMatches(userId, query, filters, asOf, strip(after, RANKED), limit));
        }
        if (hits.size() < limit) {
            hits.addAll(descriptionOnlyMatches(userId, query, filters, asOf, strip(after, DESCRIPTION_ONLY),
                    limit - hits.size()));
        }
        return hits;
    }

    private List<Hit> rankedMatches(UUID userId, String query, JobFilters filters, Instant asOf, After after,
            int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("q", query);
        StringBuilder sql = new StringBuilder("""
                select t.id, t.score from (
                  select j.id, (""" + SCORE + """
                  )::float8 as score
                    from jobs j cross join (select websearch_to_tsquery('english', :q) as tsq) q
                   where j.status = 'ACTIVE' and j.search_head @@ q.tsq and j.search @@ q.tsq
                """);
        visibleTo(sql, params, userId, filters, asOf);
        sql.append(") t\n");
        if (after != null) {
            sql.append(" where (t.score, t.id) < (:cScore, :cId)\n");
            params.put("cScore", Double.parseDouble(after.key()));
            params.put("cId", after.id());
        }
        sql.append(" order by t.score desc, t.id desc limit :limit");
        params.put("limit", limit);
        params.put("asOf", utc(asOf));
        return run(sql, params, (rs, row) -> new Hit(rs.getObject("id", UUID.class),
                RANKED + Double.toString(rs.getDouble("score")), 0));
    }

    private List<Hit> descriptionOnlyMatches(UUID userId, String query, JobFilters filters, Instant asOf, After after,
            int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("q", query);
        StringBuilder sql = new StringBuilder("""
                select j.id, j.sort_at
                  from jobs j cross join (select websearch_to_tsquery('english', :q) as tsq) q
                 where j.status = 'ACTIVE' and j.search @@ q.tsq and not (j.search_head @@ q.tsq)
                """);
        visibleTo(sql, params, userId, filters, asOf);
        if (after != null) {
            sql.append(" and (j.sort_at, j.id) < (:cSort, :cId)\n");
            params.put("cSort", OffsetDateTime.parse(after.key()));
            params.put("cId", after.id());
        }
        sql.append(" order by j.sort_at desc, j.id desc limit :limit");
        params.put("limit", limit);
        return run(sql, params, (rs, row) -> new Hit(rs.getObject("id", UUID.class),
                DESCRIPTION_ONLY + rs.getObject("sort_at", OffsetDateTime.class), 0));
    }

    /** The position without its tier prefix, or null when the cursor is not in that tier. */
    private static After strip(After after, String prefix) {
        return after == null || !after.key().startsWith(prefix) ? null
                : new After(after.key().substring(prefix.length()), after.id());
    }

    /** No keyword: newest first. */
    List<Hit> recent(UUID userId, JobFilters filters, Instant asOf, After after, int limit) {
        limitTime();
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("select j.id, j.sort_at from jobs j where j.status = 'ACTIVE'\n");
        visibleTo(sql, params, userId, filters, asOf);
        if (after != null) {
            sql.append(" and (j.sort_at, j.id) < (:cSort, :cId)\n");
            params.put("cSort", OffsetDateTime.parse(after.key()));
            params.put("cId", after.id());
        }
        sql.append(" order by j.sort_at desc, j.id desc limit :limit");
        params.put("limit", limit);
        return run(sql, params, (rs, row) -> new Hit(rs.getObject("id", UUID.class),
                rs.getObject("sort_at", OffsetDateTime.class).toString(), 0));
    }

    SimilarSource similarSource(UUID jobId) {
        return jdbc.sql("select embedding is not null as embedded, embedding_model from jobs where id = :id")
                .param("id", jobId)
                .query((rs, row) -> new SimilarSource(rs.getBoolean("embedded"), rs.getString("embedding_model")))
                .optional().orElse(null);
    }

    /**
     * The nearest neighbours of a job by cosine distance, nearest first, as a keyset slice of the top
     * {@code similarNeighbours}. Filters and the user's hidden jobs are applied inside the index scan: with pgvector's
     * iterative scan the HNSW index keeps searching until it has found enough rows that pass them (ADR 0023).
     * Neighbours are compared only with vectors of the source's own embedding model.
     */
    List<Hit> similar(UUID userId, UUID sourceId, String model, JobFilters filters, Instant asOf, After after, int limit) {
        limitTime();
        jdbc.sql("select set_config('hnsw.ef_search', :ef, true), set_config('hnsw.iterative_scan', 'relaxed_order', true)")
                .param("ef", Integer.toString(properties.efSearch())).query().listOfRows();
        Map<String, Object> params = new HashMap<>();
        params.put("src", sourceId);
        params.put("model", model);
        params.put("neighbours", properties.similarNeighbours());
        StringBuilder sql = new StringBuilder("""
                select t.id, t.dist from (
                  select j.id, (j.embedding <=> (select embedding from jobs where id = :src))::float8 as dist
                    from jobs j
                   where j.status = 'ACTIVE' and j.id <> :src and j.embedding is not null
                     and j.embedding_model = :model
                """);
        visibleTo(sql, params, userId, filters, asOf);
        sql.append("""
                   order by j.embedding <=> (select embedding from jobs where id = :src)
                   limit :neighbours
                ) t
                """);
        if (after != null) {
            sql.append(" where (t.dist, t.id) > (:cDist, :cId)\n");
            params.put("cDist", Double.parseDouble(after.key()));
            params.put("cId", after.id());
        }
        sql.append(" order by t.dist, t.id limit :limit");
        params.put("limit", limit);
        return run(sql, params, (rs, row) -> {
            double dist = rs.getDouble("dist");
            return new Hit(rs.getObject("id", UUID.class), Double.toString(dist), 1.0 - dist);
        });
    }

    /** The caller's saved jobs, most recently saved first (any status: a saved job that expired stays listed). */
    List<Hit> saved(UUID userId, After after, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        StringBuilder sql = new StringBuilder("""
                select a.job_id, a.created_at from user_job_actions a
                 where a.user_id = :userId and a.action = 'SAVED'
                """);
        if (after != null) {
            sql.append(" and (a.created_at, a.job_id) < (:cAt, :cId)\n");
            params.put("cAt", OffsetDateTime.parse(after.key()));
            params.put("cId", after.id());
        }
        sql.append(" order by a.created_at desc, a.job_id desc limit :limit");
        params.put("limit", limit);
        return run(sql, params, (rs, row) -> new Hit(rs.getObject("job_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toString(), 0));
    }

    // --- filters shared by the searches ---

    /** Appends the conditions every search applies: the user's hidden jobs and the filters, all ANDed. */
    private static void visibleTo(StringBuilder sql, Map<String, Object> params, UUID userId, JobFilters f,
            Instant asOf) {
        sql.append("""
                   and not exists (select 1 from user_job_actions h
                                    where h.user_id = :userId and h.job_id = j.id and h.action = 'HIDDEN')
                """);
        params.put("userId", userId);
        if (!f.workModes().isEmpty()) {
            sql.append(" and j.work_mode in (:workModes)\n");
            params.put("workModes", f.workModes());
        }
        if (!f.employmentTypes().isEmpty()) {
            sql.append(" and j.employment_type in (:employmentTypes)\n");
            params.put("employmentTypes", f.employmentTypes());
        }
        if (!f.seniorities().isEmpty()) {
            sql.append(" and j.seniority in (:seniorities)\n");
            params.put("seniorities", f.seniorities());
        }
        if (!f.countries().isEmpty()) {
            sql.append(" and j.country in (:countries)\n");
            params.put("countries", f.countries());
        }
        if (f.city() != null) {
            sql.append(" and lower(j.city) = :city\n");
            params.put("city", f.city());
        }
        if (f.companyId() != null) {
            sql.append(" and j.company_id = :companyId\n");
            params.put("companyId", f.companyId());
        }
        if (f.currency() != null) {
            sql.append(" and j.salary_currency = :currency\n");
            params.put("currency", f.currency());
        }
        if (f.minSalary() != null) {
            sql.append(" and j.salary_annual_top >= :minSalary\n");
            params.put("minSalary", f.minSalary());
        }
        if (f.postedWithinDays() != null) {
            sql.append(" and j.sort_at >= cast(:since as timestamptz)\n");
            params.put("since", utc(asOf.minus(java.time.Duration.ofDays(f.postedWithinDays()))));
        }
    }

    // --- display columns ---

    /** A job as shown in a list (display columns only), keyed by id. */
    Map<UUID, JobDtos.JobSummary> summaries(UUID userId, Collection<UUID> ids) {
        Map<UUID, JobDtos.JobSummary> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                select j.id, j.title, j.company_id, c.name as company_name, j.location_raw, j.city, j.country,
                       j.work_mode, j.employment_type, j.seniority, j.salary_min, j.salary_max, j.salary_currency,
                       j.salary_period, j.posted_at, j.status, left(j.description_text, 400) as summary,
                       exists (select 1 from user_job_actions a
                                where a.user_id = :userId and a.job_id = j.id and a.action = 'SAVED') as saved,
                       exists (select 1 from user_job_actions a
                                where a.user_id = :userId and a.job_id = j.id and a.action = 'APPLIED') as applied
                  from jobs j join companies c on c.id = j.company_id
                 where j.id in (:ids)
                """)
                .param("userId", userId).param("ids", List.copyOf(ids))
                .query((rs, row) -> {
                    JobDtos.JobSummary summary = new JobDtos.JobSummary(rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            new CompanyRef(rs.getObject("company_id", UUID.class), rs.getString("company_name")),
                            rs.getString("location_raw"), rs.getString("city"), rs.getString("country"),
                            rs.getString("work_mode"), rs.getString("employment_type"), rs.getString("seniority"),
                            salary(rs), instant(rs.getObject("posted_at", OffsetDateTime.class)),
                            rs.getString("status"), summaryText(rs.getString("summary")), rs.getBoolean("saved"),
                            rs.getBoolean("applied"), null, null);
                    result.put(summary.id(), summary);
                    return null;
                }).list();
        return result;
    }

    /** One job in full, without its listings (they come from ingestion's public API). */
    Optional<JobDtos.JobDetail> detail(UUID userId, UUID jobId) {
        return jdbc.sql("""
                select j.id, j.title, j.company_id, c.name as company_name, j.location_raw, j.city, j.country,
                       j.work_mode, j.employment_type, j.seniority, j.salary_min, j.salary_max, j.salary_currency,
                       j.salary_period, j.posted_at, j.expires_at, j.status, j.description_text, j.skills,
                       j.apply_url, j.embedding is not null as embedded,
                       exists (select 1 from user_job_actions a
                                where a.user_id = :userId and a.job_id = j.id and a.action = 'SAVED') as saved,
                       exists (select 1 from user_job_actions a
                                where a.user_id = :userId and a.job_id = j.id and a.action = 'HIDDEN') as hidden,
                       exists (select 1 from user_job_actions a
                                where a.user_id = :userId and a.job_id = j.id and a.action = 'APPLIED') as applied
                  from jobs j join companies c on c.id = j.company_id
                 where j.id = :id
                """)
                .param("userId", userId).param("id", jobId)
                .query((rs, row) -> new JobDtos.JobDetail(rs.getObject("id", UUID.class), rs.getString("title"),
                        new CompanyRef(rs.getObject("company_id", UUID.class), rs.getString("company_name")),
                        rs.getString("location_raw"), rs.getString("city"), rs.getString("country"),
                        rs.getString("work_mode"), rs.getString("employment_type"), rs.getString("seniority"),
                        salary(rs), instant(rs.getObject("posted_at", OffsetDateTime.class)),
                        instant(rs.getObject("expires_at", OffsetDateTime.class)), rs.getString("status"),
                        rs.getString("description_text"), texts(rs.getArray("skills")), rs.getString("apply_url"),
                        List.of(), rs.getBoolean("saved"), rs.getBoolean("hidden"), rs.getBoolean("applied"),
                        rs.getBoolean("embedded")))
                .optional();
    }

    boolean exists(UUID jobId) {
        return jdbc.sql("select 1 from jobs where id = :id").param("id", jobId).query(Integer.class).optional()
                .isPresent();
    }

    // --- the caller's state ---

    /** Saves the job for the user (and un-hides it: a saved job is one they want to see). Idempotent. */
    void save(UUID userId, UUID jobId, Instant now) {
        jdbc.sql("delete from user_job_actions where user_id = :u and job_id = :j and action = 'HIDDEN'")
                .param("u", userId).param("j", jobId).update();
        insert(userId, jobId, "SAVED", now);
    }

    void unsave(UUID userId, UUID jobId) {
        remove(userId, jobId, "SAVED");
    }

    /** Hides the job from the user's searches (and un-saves it). Idempotent. */
    void hide(UUID userId, UUID jobId, Instant now) {
        remove(userId, jobId, "SAVED");
        insert(userId, jobId, "HIDDEN", now);
    }

    void unhide(UUID userId, UUID jobId) {
        remove(userId, jobId, "HIDDEN");
    }

    /** Records that the user applied (and un-hides the job: a job applied to is not one they want out of sight). */
    void markApplied(UUID userId, UUID jobId, Instant now) {
        remove(userId, jobId, "HIDDEN");
        insert(userId, jobId, "APPLIED", now);
    }

    void unmarkApplied(UUID userId, UUID jobId) {
        remove(userId, jobId, "APPLIED");
    }

    private void insert(UUID userId, UUID jobId, String action, Instant now) {
        jdbc.sql("""
                insert into user_job_actions (user_id, job_id, action, created_at)
                values (:u, :j, :a, :now) on conflict do nothing
                """)
                .param("u", userId).param("j", jobId).param("a", action).param("now", utc(now)).update();
    }

    private void remove(UUID userId, UUID jobId, String action) {
        jdbc.sql("delete from user_job_actions where user_id = :u and job_id = :j and action = :a")
                .param("u", userId).param("j", jobId).param("a", action).update();
    }

    // --- helpers ---

    /** Bounds the transaction's queries: a runaway search is cancelled, not left to hold a connection. */
    private void limitTime() {
        jdbc.sql("select set_config('statement_timeout', :ms, true)")
                .param("ms", Long.toString(properties.statementTimeout().toMillis())).query().listOfRows();
    }

    private <T> List<T> run(StringBuilder sql, Map<String, Object> params,
            org.springframework.jdbc.core.RowMapper<T> mapper) {
        try {
            return jdbc.sql(sql.toString()).params(params).query(mapper).list();
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sqlException
                    && "57014".equals(sqlException.getSQLState())) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "search_timeout",
                        "The search took too long. Narrow it down and try again.");
            }
            throw e;
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static Salary salary(java.sql.ResultSet rs) throws SQLException {
        java.math.BigDecimal min = rs.getBigDecimal("salary_min");
        java.math.BigDecimal max = rs.getBigDecimal("salary_max");
        if (min == null && max == null) {
            return null;
        }
        return new Salary(min == null ? null : min.stripTrailingZeros(), max == null ? null : max.stripTrailingZeros(),
                rs.getString("salary_currency"), rs.getString("salary_period"));
    }

    private static List<String> texts(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            out.add((String) o);
        }
        return out;
    }

    /** The first few lines of a description as one line of at most about 240 characters, cut at a word. */
    static String summaryText(String text) {
        if (text == null) {
            return null;
        }
        String line = text.replaceAll("\\s+", " ").strip();
        if (line.isEmpty()) {
            return null;
        }
        if (line.length() <= 240) {
            return line;
        }
        int cut = line.lastIndexOf(' ', 240);
        return line.substring(0, cut > 120 ? cut : 240).strip() + "…";
    }
}
