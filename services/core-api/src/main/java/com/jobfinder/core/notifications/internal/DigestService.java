package com.jobfinder.core.notifications.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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
import com.jobfinder.core.notifications.internal.DeliveryService.Built;
import com.jobfinder.core.notifications.internal.DeliveryService.Outcome;
import com.jobfinder.core.notifications.internal.NotificationDtos.DigestFrequency;
import com.jobfinder.core.notifications.internal.NotificationDtos.SavedSearchFrequency;
import com.jobfinder.core.notifications.internal.NotificationLog.Kind;
import com.jobfinder.core.notifications.internal.SavedSearchService.Row;
import com.jobfinder.core.notifications.internal.Windows.Window;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The daily and weekly digests (docs/adr/0028-notifications.md). Each run looks at every user who has a digest or a
 * scheduled saved search and sends what is due, at most once per window:
 *
 * <ul>
 * <li><b>"For you" digest</b> (users with digests on): the strongest matches the model scored since the lookback,
 * ranked by the feed (so hidden and applied jobs are out), never a job an earlier digest or instant alert listed,
 * and only from the score cache: it never asks the model for anything.</li>
 * <li><b>Saved-search digest</b> (each DAILY or WEEKLY search): the active jobs ingestion stored since the search's
 * watermark that match it, newest first.</li>
 * </ul>
 *
 * Both are skipped (no email, window closed) when there is nothing new. Nobody is mailed who is not an active, verified,
 * undeleted user with email on and the digests not unsubscribed. A failing user or email never stops the run.
 */
@Service
class DigestService {

    /** What a run did, in counts. */
    record Summary(int users, int sent, int skipped, int failed, int pruned) {
    }

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    private static final UUID FIRST_ID = new UUID(0L, 0L);

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

    DigestService(NotificationPreferencesService preferences, SavedSearchService searches, MailRecipients recipients,
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

    /** Sends every digest that is due at {@code now}. */
    Summary runDue(Instant now) {
        Timer.Sample sample = Timer.start(meters);
        int users = 0;
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        int pruned = 0;
        try {
            UUID after = FIRST_ID;
            while (true) {
                List<UUID> page = preferences.digestCandidates(after, properties.digest().userPageSize());
                if (page.isEmpty()) {
                    break;
                }
                for (UUID userId : page) {
                    after = userId;
                    users++;
                    try {
                        int[] counts = forUser(userId, now);
                        sent += counts[0];
                        skipped += counts[1];
                        failed += counts[2];
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("The digest run failed for user {}", userId, e);
                    }
                }
            }
            try {
                pruned = notificationLog.prune(now.minus(properties.delivery().logRetention()));
            } catch (RuntimeException e) {
                log.error("Pruning the notification log failed", e);
            }
        } finally {
            sample.stop(meters.timer("notifications.run.duration", "job", "digest"));
        }
        log.info("Digest run: users={} sent={} skipped={} failed={} pruned={}", users, sent, skipped, failed, pruned);
        return new Summary(users, sent, skipped, failed, pruned);
    }

    /** {sent, skipped, failed} for one user. */
    private int[] forUser(UUID userId, Instant now) {
        int[] counts = new int[3];
        Prefs prefs = preferences.get(userId);
        if (!prefs.digestsAllowed()) {
            return counts;
        }
        Optional<MailRecipient> to = recipients.forUser(userId);
        if (to.isEmpty()) {
            return counts;
        }
        if (prefs.digestEnabled()) {
            window(prefs, prefs.digestFrequency(), now).ifPresent(w -> tally(counts, delivery.deliver(to.get(),
                    Kind.FOR_YOU_DIGEST, null, w.key(), now, () -> forYou(to.get(), prefs, now))));
        }
        for (SavedSearchFrequency frequency : EnumSet.of(SavedSearchFrequency.DAILY, SavedSearchFrequency.WEEKLY)) {
            DigestFrequency cadence = DigestFrequency.valueOf(frequency.name());
            Optional<Window> window = window(prefs, cadence, now);
            if (window.isEmpty()) {
                continue;
            }
            for (Row search : searches.scheduled(userId, frequency)) {
                Instant until = now.minus(properties.settleDelay());
                Outcome outcome = delivery.deliver(to.get(), Kind.SAVED_SEARCH_DIGEST, search.id(),
                        window.get().key(), now, () -> savedSearch(to.get(), prefs, search, until, now));
                tally(counts, outcome);
                if (outcome == Outcome.SENT || outcome == Outcome.SKIPPED) {
                    searches.advance(search.id(), until);
                }
            }
        }
        return counts;
    }

    private Optional<Window> window(Prefs prefs, DigestFrequency frequency, Instant now) {
        return Windows.due(now, prefs.zone(), prefs.digestHour(), prefs.digestWeekday(), frequency,
                properties.digest().maxLateness());
    }

    private Optional<Built> forYou(MailRecipient to, Prefs prefs, Instant now) {
        NotificationProperties.Digest config = properties.digest();
        Instant since = now.minus(properties.delivery().logRetention());
        var exclude = notificationLog.sentJobs(to.userId(), EnumSet.of(Kind.FOR_YOU_DIGEST, Kind.INSTANT_MATCH_ALERT),
                since, null);
        // More than the email lists, so it can say how many more there are.
        List<FeedMatch> matches = feed.strongMatches(to.userId(), now.minus(config.lookback()), config.minScore(),
                exclude, config.maxItems() + 50);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return builder.matches(to.userId(), prefs, Kind.FOR_YOU_DIGEST, matches, config.maxItems(), now);
    }

    private Optional<Built> savedSearch(MailRecipient to, Prefs prefs, Row search, Instant until, Instant now) {
        NewJobs found = newJobs.newSince(to.userId(), search.criteria(), search.lastRunAt(), until,
                properties.digest().maxItems());
        if (found.total() == 0) {
            return Optional.empty();
        }
        return builder.search(to.userId(), prefs, Kind.SAVED_SEARCH_DIGEST, search, found, now);
    }

    private static void tally(int[] counts, Outcome outcome) {
        switch (outcome) {
            case SENT -> counts[0]++;
            case SKIPPED -> counts[1]++;
            case FAILED -> counts[2]++;
            case NOT_CLAIMED -> {
            }
        }
    }
}
