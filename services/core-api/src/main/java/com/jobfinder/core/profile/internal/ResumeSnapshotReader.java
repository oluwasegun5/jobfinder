package com.jobfinder.core.profile.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.profile.ResumeContents;
import com.jobfinder.core.profile.ResumeSnapshot;

/** Implements the module's public read API for resume content, scoped by owner in the query itself. */
@Component
class ResumeSnapshotReader implements ResumeContents {

    private final JdbcClient jdbc;

    ResumeSnapshotReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ResumeSnapshot> latest(UUID userId, UUID resumeId) {
        return jdbc.sql("""
                select r.label, v.id as version_id, v.structured::text as structured
                  from resumes r join resume_versions v on v.resume_id = r.id
                 where r.id = :id and r.user_id = :userId
                 order by v.version_number desc limit 1
                """).param("id", resumeId).param("userId", userId)
                .query((rs, row) -> new ResumeSnapshot(resumeId, rs.getObject("version_id", UUID.class),
                        rs.getString("label"), rs.getString("structured")))
                .optional();
    }
}
