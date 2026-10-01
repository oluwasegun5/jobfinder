package com.jobfinder.core.matching.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The score cache, {@code match_scores} (migration V23). One row per (resume version, job, prompt version); a row
 * holds the model's score and reasons plus the two content hashes it was made from, and is valid only while both
 * hashes still equal the current ones. Rewriting a row whose job or resume content changed (same key, new hashes)
 * is an upsert.
 */
@Component
class MatchScoreStore {

    record Row(UUID jobId, String resumeHash, String jobHash, int llmScore, double stage2Score,
            List<String> strengths, List<String> gaps, String model, Instant computedAt) {
    }

    /** What is written when the model scores a job. */
    record NewScore(UUID userId, UUID resumeVersionId, UUID jobId, String promptVersion, String resumeHash,
            String jobHash, Double cosine, Double skillOverlap, double recency, double stage2Score, int llmScore,
            List<String> strengths, List<String> gaps, String model) {
    }

    private final JdbcClient jdbc;

    MatchScoreStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The cached rows for these jobs, whether or not they are still valid (the caller compares the hashes). */
    Map<UUID, Row> find(UUID resumeVersionId, String promptVersion, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Row> rows = new HashMap<>();
        jdbc.sql("""
                select job_id, resume_hash, job_hash, llm_score, stage2_score, strengths, gaps, model, computed_at
                  from match_scores
                 where resume_version_id = :resumeVersionId and prompt_version = :promptVersion
                   and job_id in (:jobIds)
                """)
                .param("resumeVersionId", resumeVersionId).param("promptVersion", promptVersion)
                .param("jobIds", jobIds)
                .query((rs, row) -> new Row(rs.getObject("job_id", UUID.class), rs.getString("resume_hash").strip(),
                        rs.getString("job_hash").strip(), rs.getInt("llm_score"), rs.getDouble("stage2_score"),
                        texts(rs.getArray("strengths")), texts(rs.getArray("gaps")), rs.getString("model"),
                        rs.getTimestamp("computed_at").toInstant()))
                .list().forEach(r -> rows.put(r.jobId(), r));
        return rows;
    }

    void upsert(NewScore s) {
        jdbc.sql("""
                insert into match_scores (id, user_id, resume_version_id, job_id, prompt_version, resume_hash, job_hash,
                                          vector_score, skill_overlap, recency_score, stage2_score, llm_score,
                                          strengths, gaps, model, created_at, computed_at)
                values (:id, :userId, :resumeVersionId, :jobId, :promptVersion, :resumeHash, :jobHash,
                        :cosine, :skillOverlap, :recency, :stage2, :llmScore, :strengths, :gaps, :model, now(), now())
                on conflict (resume_version_id, job_id, prompt_version) do update set
                    resume_hash = excluded.resume_hash, job_hash = excluded.job_hash,
                    vector_score = excluded.vector_score, skill_overlap = excluded.skill_overlap,
                    recency_score = excluded.recency_score, stage2_score = excluded.stage2_score,
                    llm_score = excluded.llm_score, strengths = excluded.strengths, gaps = excluded.gaps,
                    model = excluded.model, computed_at = now()
                """)
                .param("id", UUID.randomUUID()).param("userId", s.userId())
                .param("resumeVersionId", s.resumeVersionId()).param("jobId", s.jobId())
                .param("promptVersion", s.promptVersion()).param("resumeHash", s.resumeHash())
                .param("jobHash", s.jobHash()).param("cosine", s.cosine(), java.sql.Types.NUMERIC)
                .param("skillOverlap", s.skillOverlap(), java.sql.Types.NUMERIC).param("recency", s.recency())
                .param("stage2", s.stage2Score()).param("llmScore", s.llmScore())
                .param("strengths", s.strengths().toArray(String[]::new))
                .param("gaps", s.gaps().toArray(String[]::new)).param("model", s.model())
                .update();
    }

    /**
     * Deletes scores whose resume version the user has since been scored against with a newer one, once they are older
     * than {@code supersededAfter}, and any score older than {@code maxAge}. Returns how many rows went.
     */
    int prune(Duration supersededAfter, Duration maxAge) {
        int superseded = jdbc.sql("""
                delete from match_scores m
                 where m.computed_at < now() - make_interval(secs => :supersededSecs)
                   and exists (select 1 from match_scores n
                                where n.user_id = m.user_id and n.resume_version_id <> m.resume_version_id
                                  and n.computed_at > m.computed_at)
                """).param("supersededSecs", supersededAfter.toSeconds()).update();
        int old = jdbc.sql("delete from match_scores where computed_at < now() - make_interval(secs => :maxSecs)")
                .param("maxSecs", maxAge.toSeconds()).update();
        return superseded + old;
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
