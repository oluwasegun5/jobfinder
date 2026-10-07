package com.jobfinder.core.notifications.internal;

import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionProperties;
import com.jobfinder.core.compliance.RetentionTask;

/** The log of emails sent is audit and de-duplication only: rows older than the retention period are deleted. */
@Component
class NotificationLogRetention implements RetentionTask {

    private final JdbcClient jdbc;
    private final RetentionProperties properties;

    NotificationLogRetention(JdbcClient jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "notification-log";
    }

    @Override
    public int purge(Instant now) {
        return jdbc.sql("delete from notification_log where created_at < :cutoff and status <> 'PENDING'")
                .param("cutoff", now.minus(properties.notificationLog()).atOffset(ZoneOffset.UTC)).update();
    }
}
