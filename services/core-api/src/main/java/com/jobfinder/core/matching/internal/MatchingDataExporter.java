package com.jobfinder.core.matching.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;

/** Matching's share of the data export: the score and explanation computed for each job against the user's CV. */
@Component
class MatchingDataExporter implements UserDataExporter {

    private final JdbcClient jdbc;

    MatchingDataExporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "matching";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("match-scores", UserDataJson.rows(jdbc,
                "select t.* from match_scores t where t.user_id = :userId order by t.computed_at, t.id", userId,
                "resume_hash", "job_hash"));
    }
}
