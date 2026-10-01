package com.jobfinder.core.notifications.internal;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.jobfinder.core.matching.MatchesRefreshed;

/**
 * Alerts the user about strong new matches after a ranking run refreshed their scores (docs/adr/0028-notifications.md).
 * Asynchronous and failure-proof: the run that published the event never waits for the mail server and never sees an
 * error from here.
 */
@Component
@ConditionalOnProperty(prefix = "app.notifications.instant", name = "enabled", havingValue = "true", matchIfMissing = true)
class MatchesRefreshedListener {

    private static final Logger log = LoggerFactory.getLogger(MatchesRefreshedListener.class);

    private final InstantAlertService alerts;
    private final Clock clock;

    MatchesRefreshedListener(InstantAlertService alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    @Async
    @EventListener
    void on(MatchesRefreshed event) {
        try {
            alerts.alertMatches(event.userId(), Instant.ofEpochMilli(clock.millis()));
        } catch (RuntimeException e) {
            log.error("Instant alert after a matching run failed for user {}", event.userId(), e);
        }
    }
}
