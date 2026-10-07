package com.jobfinder.core.jobs.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;

/** Jobs' share of the data export: the jobs the user saved, hid, viewed or applied to. */
@Component
class JobsDataExporter implements UserDataExporter {

    private final JdbcClient jdbc;

    JobsDataExporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "jobs";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("job-actions", UserDataJson.rows(jdbc,
                "select t.* from user_job_actions t where t.user_id = :userId order by t.created_at, t.job_id", userId));
    }
}
