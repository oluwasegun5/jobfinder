package com.jobfinder.core.billing.internal;

import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionProperties;
import com.jobfinder.core.compliance.RetentionTask;

/**
 * The webhook log (provider event ids only) exists to drop a redelivered event. Providers stop retrying after days, so
 * rows far older than that are deleted. The credit ledger has its own idempotency keys and is never pruned here.
 */
@Component
class WebhookEventRetention implements RetentionTask {

    private final JdbcClient jdbc;
    private final RetentionProperties properties;

    WebhookEventRetention(JdbcClient jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "webhook-events";
    }

    @Override
    public int purge(Instant now) {
        return jdbc.sql("delete from webhook_events where received_at < :cutoff")
                .param("cutoff", now.minus(properties.webhookEvents()).atOffset(ZoneOffset.UTC)).update();
    }
}
