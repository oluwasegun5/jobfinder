package com.jobfinder.core.notifications.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jobfinder.core.feed.FeedMatch;
import com.jobfinder.core.feed.FeedMatches;
import com.jobfinder.core.identity.MailRecipient;
import com.jobfinder.core.identity.MailRecipients;
import com.jobfinder.core.jobs.NewJobs;
import com.jobfinder.core.jobs.NewJobsSource;
import com.jobfinder.core.notifications.internal.DeliveryService.Outcome;
import com.jobfinder.core.notifications.internal.NotificationLog.Kind;
import com.jobfinder.core.notifications.internal.SavedSearchService.Row;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Instant alerts (docs/adr/0028-notifications.md), two kinds:
 *
 * <ul>
 * <li><b>Strong matches</b>, triggered by the {@code MatchesRefreshed} event of a ranking run (the nightly batch above
 * all): the user's feed matches that the model scored recently, whose feed score is at or above the user's threshold
 * and that no digest or alert listed before. It reads the score cache only, so an alert never costs a model call or any
 * of the user's AI allowance.</li>
 * <li><b>Saved searches set to INSTANT</b>, found by a poll every few minutes: the jobs ingestion stored since the
 * search's watermark.</li>
 * </ul>
 *
 * Each user gets at most {@code max-per-day} alert emails in any 24 hours; what the cap holds back is not lost, the
 * watermark (searches) or the unsent set (matches) keeps it for the next alert. Dedup is twofold: a job is never listed
 * twice, and a window key makes concurrent or repeated evaluations of the same jobs claim one email.
 */
@Service
class InstantAlertService {

    /** What a poll did, in counts. */
    record Summary(int searches, int sent, int rateLimited, int failed) {
    }

    private static final Logger log = LoggerFactory.getLogger(InstantAlertService.class);
    private static final UUID FIRST_ID = new UUID(0L, 0L);
    private static final Set<Kind> ALERTS = EnumSet.of(Kind.INSTANT_MATCH_ALERT, Kind.INSTANT_SEARCH_ALERT);

    private final NotificationPreferencesService preferences;
    private final SavedSearchService searches;
    private final MailRecipients recipients;
    private final FeedMatches feed;
    private final NewJobsSource newJobs;
    private final MailBuilder builder;
    private final DeliveryService delivery;
    private final NotificationLog notificationLog;
    private final NotificationProperties properties;
    private final MeterRegistry meters;

    InstantAlertService(NotificationPreferencesService preferences, SavedSearchService searches, MailRecipients recipients,
            FeedMatches feed, NewJobsSource newJobs, MailBuilder builder, DeliveryService delivery,
            NotificationLog notificationLog, NotificationProperties properties, MeterRegistry meters) {
        this.preferences = preferences;
        this.searches = searches;
        this.recipients = recipients;
        this.feed = feed;
        this.newJobs = newJobs;
        this.builder = builder;
        this.delivery = delivery;
        this.notificationLog = notificationLog;
        this.properties = properties;
        this.meters = meters;
    }

    /** Alerts the user about strong new matches, if they asked for that and are allowed to be mailed. Never throws. */
    Outcome alertMatches(UUID userId, Instant now) {
        try {
            Prefs prefs = preferences.get(userId);
            if (!prefs.instantEnabled() || !prefs.alertsAllowed()) {
                return Outcome.NOT_CLAIMED;
            }
            Optional<MailRecipient> to = recipients.forUser(userId);
            if (to.isEmpty()) {
                return Outcome.NOT_CLAIMED;
            }
            if (capReached(userId, now)) {
                meters.counter("notifications.alerts.rate_limited", "kind", Kind.INSTANT_MATCH_ALERT.name())
                        .increment();
                return Outcome.NOT_CLAIMED;
            }
            NotificationProperties.Alerts config = properties.instant();
            Set<UUID> exclude = notificationLog.sentJobs(userId,
                    EnumSet.of(Kind.FOR_YOU_DIGEST, Kind.INSTANT_MATCH_ALERT),
                    now.minus(properties.delivery().logRetention()), null);
            List<FeedMatch> matches = feed.strongMatches(userId, now.minus(config.lookback()),
                    prefs.instantThreshold(), exclude, config.maxItemsPerAlert() + 50);
            if (matches.isEmpty()) {
                return Outcome.NOT_CLAIMED;
            }
            String window = "M:" + fingerprint(matches.stream().map(FeedMatch::jobId).toList());
            return delivery.deliver(to.get(), Kind.INSTANT_MATCH_ALERT, null, window, now,
                    () -> builder.matches(userId, prefs, Kind.INSTANT_MATCH_ALERT, matches, config.maxItemsPerAlert(),
                            now));
        } catch (RuntimeException e) {
            log.error("Instant match alert failed for user {}", userId, e);
            return Outcome.FAILED;
        }
    }

    /** Looks at every INSTANT saved search and mails the new jobs of those that have some. */
    Summary pollSearches(Instant now) {
        Timer.Sample sample = Timer.start(meters);
        int count = 0;
        int sent = 0;
        int limited = 0;
        int failed = 0;
        try {
            UUID after = FIRST_ID;
            while (true) {
                List<Row> page = searches.instantAfter(after, 200);
                if (page.isEmpty()) {
                    break;
                }
                for (Row search : page) {
                    after = search.id();
                    count++;
                    try {
                        switch (pollSearch(search, now)) {
                            case SENT -> sent++;
                            case FAILED -> failed++;
                            case LIMITED -> limited++;
                            case NOTHING -> {
                            }
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("Instant search alert failed for saved search {}", search.id(), e);
                    }
                }
            }
        } finally {
            sample.stop(meters.timer("notifications.run.duration", "job", "instant"));
        }
        return new Summary(count, sent, limited, failed);
    }

    /** What polling one search came to: "limited" is held back by the daily cap, "nothing" is no work or no mail. */
    private enum Polled {
        SENT, FAILED, LIMITED, NOTHING
    }

    private Polled pollSearch(Row search, Instant now) {
        UUID userId = search.userId();
        Instant until = now.minus(properties.settleDelay());
        Prefs prefs = preferences.get(userId);
        Optional<MailRecipient> to = prefs.alertsAllowed() ? recipients.forUser(userId) : Optional.empty();
        if (to.isEmpty()) {
            // Not mailable now: what appeared meanwhile is dropped, so turning mail back on does not flood.
            searches.advance(search.id(), until);
            return Polled.NOTHING;
        }
        NewJobs found = newJobs.newSince(userId, search.criteria(), search.lastRunAt(), until,
                properties.instant().maxItemsPerAlert());
        if (found.total() == 0) {
            searches.advance(search.id(), until);
            return Polled.NOTHING;
        }
        if (capReached(userId, now)) {
            meters.counter("notifications.alerts.rate_limited", "kind", Kind.INSTANT_SEARCH_ALERT.name()).increment();
            return Polled.LIMITED;
        }
        Outcome outcome = delivery.deliver(to.get(), Kind.INSTANT_SEARCH_ALERT, search.id(),
                "S:" + search.lastRunAt().toEpochMilli(), now,
                () -> builder.search(userId, prefs, Kind.INSTANT_SEARCH_ALERT, search, found, now));
        if (outcome == Outcome.SENT || outcome == Outcome.SKIPPED) {
            searches.advance(search.id(), until);
        }
        return outcome == Outcome.SENT ? Polled.SENT : outcome == Outcome.FAILED ? Polled.FAILED : Polled.NOTHING;
    }

    private boolean capReached(UUID userId, Instant now) {
        return notificationLog.sentCount(userId, ALERTS, now.minus(Duration.ofHours(24)))
                >= properties.instant().maxPerDay();
    }

    /** A short stable name for a set of jobs: the same set always claims the same window. */
    private static String fingerprint(List<UUID> ids) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ids.stream().map(UUID::toString).sorted()
                    .forEach(id -> digest.update((id + ",").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
