package com.jobfinder.core.compliance;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention periods ({@code app.retention.*}, docs/adr/0040-compliance-and-account-data.md; the user-facing table is in
 * docs/compliance/data-inventory.md). A period is how long the data may exist AFTER it was created or last needed;
 * every one must be positive.
 *
 * @param enabled              runs the daily job; switch off only in tests
 * @param runInterval          time between runs
 * @param initialDelay         wait after start-up before the first run
 * @param rawPostings          raw job postings kept for reprocessing (PLAN.md section 5: 30 days)
 * @param expiredTokensGrace   how long an expired sign-in or email token stays before it is deleted
 * @param unverifiedAccounts   an account whose email was never verified is deleted (with everything it holds) after this
 * @param webhookEvents        payment-webhook delivery ids kept for de-duplication, far longer than any provider retries
 * @param notificationLog      the log of emails sent
 * @param renderedFiles        cached PDF/DOCX files generated from a CV or document (they can be rendered again)
 */
@ConfigurationProperties("app.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("24h") Duration runInterval,
        @DefaultValue("10m") Duration initialDelay,
        @DefaultValue("30d") Duration rawPostings,
        @DefaultValue("7d") Duration expiredTokensGrace,
        @DefaultValue("30d") Duration unverifiedAccounts,
        @DefaultValue("180d") Duration webhookEvents,
        @DefaultValue("365d") Duration notificationLog,
        @DefaultValue("90d") Duration renderedFiles) {

    public RetentionProperties {
        require("run-interval", runInterval);
        require("raw-postings", rawPostings);
        require("expired-tokens-grace", expiredTokensGrace);
        require("unverified-accounts", unverifiedAccounts);
        require("webhook-events", webhookEvents);
        require("notification-log", notificationLog);
        require("rendered-files", renderedFiles);
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("app.retention.initial-delay must not be negative");
        }
    }

    private static void require(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.retention." + name + " must be a positive duration");
        }
    }
}
