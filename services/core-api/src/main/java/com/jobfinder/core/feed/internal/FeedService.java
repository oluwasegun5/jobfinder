package com.jobfinder.core.feed.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jobfinder.core.feed.internal.FeedDtos.EmptyReason;
import com.jobfinder.core.feed.internal.FeedDtos.FeedItem;
import com.jobfinder.core.feed.internal.FeedDtos.FeedPage;
import com.jobfinder.core.feed.internal.FeedDtos.ScoreSource;
import com.jobfinder.core.feed.internal.FeedbackAdjuster.Adjustment;
import com.jobfinder.core.feed.internal.FeedbackAdjuster.Prepared;
import com.jobfinder.core.jobs.JobCard;
import com.jobfinder.core.jobs.JobFeatures;
import com.jobfinder.core.jobs.JobFeedbackSource;
import com.jobfinder.core.matching.MatchResult;
import com.jobfinder.core.matching.MatchService;
import com.jobfinder.core.matching.MatchStatus;
import com.jobfinder.core.matching.RankedMatches;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidateProfiles;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.databind.json.JsonMapper;

/**
 * The "For you" feed (docs/adr/0027-feed-and-feedback.md): the user's matches read from the score cache, moved by the
 * user's own saves, hides and applications, cursor-paged. It never asks the model about a job except for one case, a
 * user whose cache holds no model score at all yet (their first look), which runs the existing
 * {@link MatchService#rankedMatches} once; everything else is cached scores plus arithmetic.
 */
@Service
class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    /** A match with its adjustment, ready to be ordered. */
    private record Entry(MatchResult match, Adjustment adjustment, double feedScore, int tier, long feedMillis,
            long stage2Millis) {
    }

    private static final Comparator<Entry> ORDER = Comparator.comparingInt(Entry::tier)
            .thenComparing(Comparator.comparingLong(Entry::feedMillis).reversed())
            .thenComparing(Comparator.comparingLong(Entry::stage2Millis).reversed())
            .thenComparing(e -> e.match().jobId());

    private final CandidateProfiles profiles;
    private final MatchService matches;
    private final JobFeedbackSource jobs;
    private final FeedProperties properties;
    private final FeedbackAdjuster adjuster;
    private final JsonMapper json;
    private final Clock clock;

    FeedService(CandidateProfiles profiles, MatchService matches, JobFeedbackSource jobs, FeedProperties properties,
            JsonMapper json, Clock clock) {
        this.profiles = profiles;
        this.matches = matches;
        this.jobs = jobs;
        this.properties = properties;
        this.adjuster = new FeedbackAdjuster(properties.feedback());
        this.json = json;
        this.clock = clock;
    }

    FeedPage page(UUID userId, Integer requestedLimit, String token) {
        int limit = requestedLimit == null ? FeedDtos.DEFAULT_LIMIT : requestedLimit;
        FeedCursor cursor = token == null || token.isBlank() ? null : FeedCursor.decode(json, token);
        boolean first = cursor == null;

        Optional<Candidate> candidate = profiles.candidate(userId);
        if (candidate.isEmpty()) {
            return empty(first, EmptyReason.NO_RESUME);
        }
        if (!candidate.get().hasPreferences()) {
            return empty(first, EmptyReason.NO_PREFERENCES);
        }
        RankedMatches pool;
        try {
            pool = pool(userId, first);
        } catch (ApiException e) {
            return switch (e.code()) {
                case "resume_embedding_pending" -> empty(first, EmptyReason.RESUME_PROCESSING);
                case "resume_required" -> empty(first, EmptyReason.NO_RESUME);
                default -> throw e;
            };
        }

        Instant asOf = first ? Instant.ofEpochMilli(clock.millis()) : cursor.asOfInstant();
        List<Entry> ranked = rank(userId, pool, asOf);
        if (!first) {
            ranked = ranked.stream().filter(e -> after(e, cursor)).toList();
        }
        if (ranked.isEmpty()) {
            return empty(first, EmptyReason.NO_MATCHES);
        }
        boolean more = ranked.size() > limit;
        List<Entry> page = more ? ranked.subList(0, limit) : ranked;
        Map<UUID, JobCard> cards = jobs.cards(userId, page.stream().map(e -> e.match().jobId()).toList());
        List<FeedItem> items = new ArrayList<>();
        for (Entry e : page) {
            JobCard card = cards.get(e.match().jobId());
            if (card != null) { // deleted since the pool was read
                items.add(item(card, e));
            }
        }
        String next = null;
        if (more) {
            Entry last = page.get(page.size() - 1);
            next = new FeedCursor(FeedCursor.VERSION, asOf.toEpochMilli(), last.tier(), last.feedMillis(),
                    last.stage2Millis(), last.match().jobId()).encode(json);
        }
        return new FeedPage(items, next, null);
    }

    /**
     * The cached matches of the user. Their very first look finds no model score at all: the existing on-demand
     * ranking runs once to fill the cache (bounded by the user's daily allowance and falling back to estimates when
     * it is used up or ai-service is down), and the cache is read again.
     */
    private RankedMatches pool(UUID userId, boolean first) {
        RankedMatches pool = matches.cachedMatches(userId, properties.poolSize());
        if (first && pool.stats().recalled() > 0 && pool.stats().cached() == 0) {
            try {
                matches.rankedMatches(userId);
                pool = matches.cachedMatches(userId, properties.poolSize());
            } catch (ApiException e) {
                log.debug("First-look scoring skipped: {}", e.code());
            }
        }
        return pool;
    }

    private List<Entry> rank(UUID userId, RankedMatches pool, Instant asOf) {
        FeedProperties.Feedback config = properties.feedback();
        List<UUID> ids = pool.matches().stream().map(MatchResult::jobId).toList();
        Map<UUID, JobFeatures> features = jobs.features(userId, ids);
        List<Prepared> signals = adjuster.prepare(
                jobs.signals(userId, asOf.minus(config.window()), asOf, config.maxSignalsPerAction()));
        List<Entry> entries = new ArrayList<>();
        for (MatchResult m : pool.matches()) {
            JobFeatures f = features.get(m.jobId());
            if (f == null || f.applied()) {
                continue; // gone, or applied to: it leaves the feed (ADR 0027)
            }
            Adjustment a = adjuster.adjust(m.jobId(), f.companyId(), TitleTokens.of(f.title()), signals, asOf);
            double feed = Math.max(0, Math.min(100, m.score() + a.points()));
            entries.add(new Entry(m, a, feed, m.status() == MatchStatus.LLM_SCORED ? 0 : 1, Math.round(feed * 1000),
                    Math.round(m.stage2Score() * 1000)));
        }
        entries.sort(ORDER);
        return entries;
    }

    private static boolean after(Entry e, FeedCursor c) {
        int byTier = Integer.compare(e.tier(), c.tier());
        if (byTier != 0) {
            return byTier > 0;
        }
        int byFeed = Long.compare(c.feedMillis(), e.feedMillis()); // higher feed score sorts first
        if (byFeed != 0) {
            return byFeed > 0;
        }
        int byStage2 = Long.compare(c.stage2Millis(), e.stage2Millis());
        if (byStage2 != 0) {
            return byStage2 > 0;
        }
        return e.match().jobId().compareTo(c.id()) > 0;
    }

    private static FeedItem item(JobCard card, Entry e) {
        MatchResult m = e.match();
        boolean model = m.status() == MatchStatus.LLM_SCORED;
        return new FeedItem(card, m.score(), model ? ScoreSource.LLM_SCORED : ScoreSource.STAGE2_ONLY,
                Math.round(e.feedScore() * 100.0) / 100.0, Math.round(e.adjustment().points() * 100.0) / 100.0,
                e.adjustment().reasons(), m.strengths(), m.gaps(), m.reason(), m.model(), m.scoredAt());
    }

    private static FeedPage empty(boolean first, EmptyReason reason) {
        return new FeedPage(List.of(), null, first ? reason : null);
    }
}
