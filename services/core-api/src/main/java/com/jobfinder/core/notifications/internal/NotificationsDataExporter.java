package com.jobfinder.core.notifications.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;

/** Notifications' share of the data export: saved searches, email settings and the log of what was sent. */
@Component
class NotificationsDataExporter implements UserDataExporter {

    private final JdbcClient jdbc;

    NotificationsDataExporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "notifications";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("saved-searches", UserDataJson.rows(jdbc,
                "select t.* from saved_searches t where t.user_id = :userId order by t.created_at, t.id", userId));
        bundle.json("email-settings", UserDataJson.rows(jdbc,
                "select t.* from notification_preferences t where t.user_id = :userId", userId));
        bundle.json("email-log", UserDataJson.rows(jdbc,
                "select t.* from notification_log t where t.user_id = :userId order by t.created_at, t.id", userId));
    }
}
