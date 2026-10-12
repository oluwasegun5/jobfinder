package com.jobfinder.core.billing.internal;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * The weekly download of the disposable-domain list ({@code app.billing.trial.disposable.*}). Off by default
 * ({@code refresh-enabled=false}), so tests and local runs never touch the network; the pinned copy in the jar is used
 * until a refresh stores a newer one. Runs under a ShedLock lock so one instance downloads.
 */
@Component
class DisposableDomainRefresher {

    static final String LOCK = "billing:disposable-domains";
    private static final Logger log = LoggerFactory.getLogger(DisposableDomainRefresher.class);
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final DisposableDomains domains;
    private final TrialProperties.Disposable config;
    private final LockingTaskExecutor locks;
    private final Clock clock;

    DisposableDomainRefresher(DisposableDomains domains, TrialProperties properties, LockingTaskExecutor locks,
            Clock clock) {
        this.domains = domains;
        this.config = properties.disposable();
        this.locks = locks;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.billing.trial.disposable.refresh-cron:0 30 3 * * MON}", zone = "UTC")
    void scheduled() {
        if (!config.refreshEnabled()) {
            return;
        }
        try {
            locks.executeWithLock((LockingTaskExecutor.TaskWithResult<DisposableDomains.Outcome>) this::refresh,
                    new LockConfiguration(Instant.now(), LOCK, Duration.ofMinutes(10), Duration.ofSeconds(30)));
        } catch (Throwable e) {
            log.error("The disposable-domain refresh could not run", e);
        }
    }

    /** Downloads the list and hands it to {@link DisposableDomains#apply}; DISABLED when switched off. */
    DisposableDomains.Outcome refresh() {
        if (!config.refreshEnabled()) {
            return DisposableDomains.Outcome.DISABLED;
        }
        String body;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(config.timeout())
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(config.timeout());
            body = RestClient.builder().requestFactory(factory).build().get().uri(config.url()).retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            log.warn("Downloading the disposable-domain list failed; keeping the current one: {}", e.toString());
            return DisposableDomains.Outcome.FETCH_FAILED;
        }
        if (body != null && body.length() > MAX_BYTES) {
            log.warn("The disposable-domain download is larger than {} bytes; keeping the current list", MAX_BYTES);
            return DisposableDomains.Outcome.REJECTED_TOO_DIFFERENT;
        }
        return domains.apply(body, config.url(), clock.instant());
    }
}
