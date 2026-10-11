package com.jobfinder.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Every email core-api sends (verification, password reset, digests, instant alerts, source alerts) must carry the
 * sender from {@code NOTIFICATIONS_MAIL_FROM}: an SMTP provider refuses a From address outside its verified domain.
 * Reads the real application.yml, so a sender that is hard-coded again is caught without a database or a mail server.
 */
class MailSenderConfigTests {

    private static final String DEFAULT_SENDER = "no-reply@jobfinder.local";
    private static final String[] SENDER_KEYS = { "app.auth.mail-from", "app.ingestion.alerts.from",
            "app.notifications.mail-from" };

    @Configuration
    static class Empty {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Empty.class);

    @Test
    void oneVariableSetsTheSenderOfAllMail() {
        runner.withPropertyValues("NOTIFICATIONS_MAIL_FROM=no-reply@example.test").run(context -> {
            Environment env = context.getEnvironment();
            for (String key : SENDER_KEYS) {
                assertThat(env.getProperty(key)).as(key).isEqualTo("no-reply@example.test");
            }
        });
    }

    @Test
    void theLocalDefaultIsUnchangedWhenTheVariableIsNotSet() {
        runner.run(context -> {
            Environment env = context.getEnvironment();
            for (String key : SENDER_KEYS) {
                assertThat(env.getProperty(key)).as(key).isEqualTo(DEFAULT_SENDER);
            }
        });
    }

    @Test
    void theWebBaseUrlOfEveryEmailComesFromWebBaseUrl() {
        runner.withPropertyValues("WEB_BASE_URL=https://app.example.test").run(context -> {
            Environment env = context.getEnvironment();
            for (String key : new String[] { "app.auth.web-base-url", "app.ingestion.alerts.web-base-url",
                    "app.notifications.web-base-url" }) {
                assertThat(env.getProperty(key)).as(key).isEqualTo("https://app.example.test");
            }
        });
    }
}
