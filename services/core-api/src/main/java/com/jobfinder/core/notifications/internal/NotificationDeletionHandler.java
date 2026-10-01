package com.jobfinder.core.notifications.internal;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * The notification module's share of an account deletion: settings, saved searches and the send log. Runs in the deleting
 * transaction. (The foreign keys cascade too; this keeps the purge explicit, like every module's.)
 */
@Component
class NotificationDeletionHandler {

    private final JdbcClient jdbc;

    NotificationDeletionHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        for (String table : new String[] { "notification_log", "saved_searches", "notification_preferences" }) {
            jdbc.sql("delete from " + table + " where user_id = :userId").param("userId", event.userId()).update();
        }
    }
}
