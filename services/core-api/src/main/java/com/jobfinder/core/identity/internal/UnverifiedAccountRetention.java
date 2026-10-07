package com.jobfinder.core.identity.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionProperties;
import com.jobfinder.core.compliance.RetentionTask;

/**
 * An account whose email address was never verified holds nothing but that address and a password hash. After the
 * retention period it is deleted through the same path as a user's own deletion ({@link AuthService#deleteAccount}), so
 * every module purges whatever it holds. Admins are never touched.
 */
@Component
class UnverifiedAccountRetention implements RetentionTask {

    private static final Logger log = LoggerFactory.getLogger(UnverifiedAccountRetention.class);
    private static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final AuthService auth;
    private final RetentionProperties properties;

    UnverifiedAccountRetention(JdbcClient jdbc, AuthService auth, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "unverified-accounts";
    }

    @Override
    public int purge(Instant now) {
        List<UUID> stale = jdbc.sql("""
                select id from users
                 where email_verified_at is null and role = 'USER' and created_at < :cutoff
                 order by created_at limit :batch
                """).param("cutoff", now.minus(properties.unverifiedAccounts()).atOffset(ZoneOffset.UTC))
                .param("batch", BATCH).query(UUID.class).list();
        int deleted = 0;
        for (UUID id : stale) {
            try {
                auth.deleteAccount(id);
                deleted++;
            } catch (RuntimeException e) {
                // One account failing (say a storage outage) must not stop the others; the next run retries it.
                log.warn("Could not delete the unverified account {}", id, e);
            }
        }
        return deleted;
    }
}
