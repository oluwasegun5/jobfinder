package com.jobfinder.core.applications.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;

/** Applications' share of the data export: the tracker, its history and the reminders. */
@Component
class ApplicationsDataExporter implements UserDataExporter {

    private final JdbcClient jdbc;

    ApplicationsDataExporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "applications";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("applications", UserDataJson.rows(jdbc,
                "select t.* from applications t where t.user_id = :userId order by t.created_at, t.id", userId));
        bundle.json("events", UserDataJson.rows(jdbc,
                "select t.* from application_events t where t.user_id = :userId order by t.at, t.seq", userId));
        bundle.json("reminders", UserDataJson.rows(jdbc,
                "select t.* from reminders t where t.user_id = :userId order by t.created_at, t.id", userId));
    }
}
