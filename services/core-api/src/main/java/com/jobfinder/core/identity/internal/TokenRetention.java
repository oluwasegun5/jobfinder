package com.jobfinder.core.identity.internal;

import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionProperties;
import com.jobfinder.core.compliance.RetentionTask;

/**
 * Sign-in and email tokens are useless once expired; they are kept a short grace period (reuse detection and support
 * look at recent history), then deleted. Live tokens are never touched.
 */
@Component
class TokenRetention implements RetentionTask {

    private final JdbcClient jdbc;
    private final RetentionProperties properties;

    TokenRetention(JdbcClient jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "expired-tokens";
    }

    @Override
    public int purge(Instant now) {
        var cutoff = now.minus(properties.expiredTokensGrace()).atOffset(ZoneOffset.UTC);
        int refresh = jdbc.sql("delete from refresh_tokens where expires_at < :cutoff").param("cutoff", cutoff).update();
        int email = jdbc.sql("delete from email_tokens where expires_at < :cutoff").param("cutoff", cutoff).update();
        return refresh + email;
    }
}
