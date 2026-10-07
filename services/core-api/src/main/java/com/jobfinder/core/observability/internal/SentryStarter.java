package com.jobfinder.core.observability.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.sentry.Sentry;

/**
 * Starts Sentry when SENTRY_DSN is set (blank, the default, leaves it off and costs nothing). Errors only: tracing is
 * OpenTelemetry's job, so no performance sampling, no default PII, and every event passes {@link SentryScrubber}.
 */
@Component
class SentryStarter implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SentryStarter.class);

    private final String dsn;
    private final String environment;
    private final String release;

    SentryStarter(@Value("${app.observability.sentry.dsn:}") String dsn,
            @Value("${app.observability.sentry.environment:local}") String environment,
            @Value("${app.observability.sentry.release:}") String release) {
        this.dsn = dsn;
        this.environment = environment;
        this.release = release;
    }

    @Override
    public void afterPropertiesSet() {
        if (dsn == null || dsn.isBlank()) {
            return;
        }
        Sentry.init(options -> {
            options.setDsn(dsn);
            options.setEnvironment(environment);
            if (release != null && !release.isBlank()) {
                options.setRelease(release);
            }
            options.setSendDefaultPii(false);
            options.setTracesSampleRate(0.0);
            options.setAttachServerName(false);
            options.setBeforeSend(new SentryScrubber());
        });
        log.info("Sentry error reporting is on (environment {})", environment);
    }

    @Override
    public void destroy() {
        if (Sentry.isEnabled()) {
            Sentry.close();
        }
    }
}
