package com.jobfinder.core.notifications.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jobfinder.core.feed.FeedMatch;
import com.jobfinder.core.ingestion.JobListing;
import com.jobfinder.core.ingestion.JobListingService;
import com.jobfinder.core.ingestion.SourceAttribution;
import com.jobfinder.core.jobs.JobCard;
import com.jobfinder.core.jobs.JobFeedbackSource;
import com.jobfinder.core.jobs.JobSearchCriteria;
import com.jobfinder.core.jobs.NewJobs;
import com.jobfinder.core.notifications.internal.DeliveryService.Built;
import com.jobfinder.core.notifications.internal.MailComposer.Credit;
import com.jobfinder.core.notifications.internal.MailComposer.Item;
import com.jobfinder.core.notifications.internal.MailComposer.Layout;
import com.jobfinder.core.notifications.internal.MailComposer.Link;
import com.jobfinder.core.notifications.internal.NotificationDtos.UnsubscribeScope;
import com.jobfinder.core.notifications.internal.NotificationLog.Kind;
import com.jobfinder.core.notifications.internal.SavedSearchService.Row;

/**
 * Turns matches and new jobs into the {@link Layout} of an email (docs/adr/0028-notifications.md): job cards from the
 * jobs module, the credit each aggregator source requires from ingestion's listing API, links to the web app's job pages,
 * dates in the user's time zone, and the unsubscribe links of the kind of email it is.
 */
@Component
class MailBuilder {

    private static final int REASON_MAX = 160;

    private final JobFeedbackSource jobs;
    private final JobListingService listings;
    private final UnsubscribeTokens tokens;
    private final NotificationProperties properties;

    MailBuilder(JobFeedbackSource jobs, JobListingService listings, UnsubscribeTokens tokens,
            NotificationProperties properties) {
        this.jobs = jobs;
        this.listings = listings;
        this.tokens = tokens;
        this.properties = properties;
    }

    /**
     * An email about the user's best matches ({@link Kind#FOR_YOU_DIGEST} or {@link Kind#INSTANT_MATCH_ALERT}): at most
     * {@code maxItems} jobs, the rest counted. Jobs that expired or were deleted since they were scored are dropped.
     * Empty when none is left.
     */
    Optional<Built> matches(UUID userId, Prefs prefs, Kind kind, List<FeedMatch> matches, int maxItems, Instant now) {
        Map<UUID, JobCard> cards = jobs.cards(userId, matches.stream().map(FeedMatch::jobId).toList());
        List<FeedMatch> usable = matches.stream().filter(m -> active(cards.get(m.jobId()))).toList();
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        List<FeedMatch> listed = usable.subList(0, Math.min(maxItems, usable.size()));
        Map<UUID, List<JobListing>> sources = listings.listingsOf(listed.stream().map(FeedMatch::jobId).toList());
        List<Item> items = new ArrayList<>();
        for (FeedMatch m : listed) {
            String reason = m.strengths() == null || m.strengths().isEmpty() ? null
                    : "Why it fits: " + cut(m.strengths().get(0), REASON_MAX);
            items.add(item(cards.get(m.jobId()), prefs, "Match " + m.matchScore() + "/100", reason,
                    sources.get(m.jobId())));
        }
        int n = usable.size();
        boolean digest = kind == Kind.FOR_YOU_DIGEST;
        String subject = (digest ? "" : "Job alert: ") + n + " new strong match" + (n == 1 ? "" : "es") + " on JobFinder";
        String heading = digest ? "Your new strong matches" : "New strong matches for you";
        String intro = digest ? "These jobs match your resume and preferences best, and none was in an earlier digest."
                : "These jobs scored at or above your alert threshold.";
        List<Link> footer = new ArrayList<>();
        UnsubscribeScope main = digest ? UnsubscribeScope.DIGESTS : UnsubscribeScope.INSTANT_ALERTS;
        footer.add(link(digest ? "Stop these digests" : "Stop instant alerts", userId, main, null));
        footer.add(link("Unsubscribe from all optional JobFinder emails", userId, UnsubscribeScope.MARKETING, null));
        footer.add(new Link("Notification settings", properties.webBase() + "/settings/notifications"));
        Layout layout = new Layout(subject, heading, intro, items, usable.size() - listed.size(),
                properties.webBase() + "/feed", credits(listed.stream().map(FeedMatch::jobId).toList(), sources),
                footer, oneClick(userId, main, null), MailComposer.sentLine(now, prefs.zone()));
        return Optional.of(new Built(listed.stream().map(FeedMatch::jobId).toList(), MailComposer.compose(layout)));
    }

    /**
     * An email about the new jobs of a saved search ({@link Kind#SAVED_SEARCH_DIGEST} or
     * {@link Kind#INSTANT_SEARCH_ALERT}): the jobs in {@code found}, newest first, and how many more there were.
     */
    Optional<Built> search(UUID userId, Prefs prefs, Kind kind, Row search, NewJobs found, Instant now) {
        List<JobCard> cards = found.jobs().stream().filter(MailBuilder::active).toList();
        if (cards.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> ids = cards.stream().map(JobCard::id).toList();
        Map<UUID, List<JobListing>> sources = listings.listingsOf(ids);
        List<Item> items = cards.stream().map(c -> item(c, prefs, null, null, sources.get(c.id()))).toList();
        int total = Math.max(found.total(), cards.size());
        String name = MailComposer.oneLine(search.name());
        boolean digest = kind == Kind.SAVED_SEARCH_DIGEST;
        String subject = (digest ? "" : "Job alert: ") + total + " new job" + (total == 1 ? "" : "s") + " for \"" + name
                + "\"";
        List<Link> footer = new ArrayList<>();
        footer.add(link("Stop emails for this search", userId, UnsubscribeScope.SAVED_SEARCH, search.id()));
        footer.add(link(digest ? "Stop all digests" : "Stop instant alerts", userId,
                digest ? UnsubscribeScope.DIGESTS : UnsubscribeScope.INSTANT_ALERTS, null));
        footer.add(link("Unsubscribe from all optional JobFinder emails", userId, UnsubscribeScope.MARKETING, null));
        footer.add(new Link("Notification settings", properties.webBase() + "/settings/notifications"));
        Layout layout = new Layout(subject, total + " new job" + (total == 1 ? "" : "s") + " for \"" + name + "\"",
                "New since your last " + (digest ? "digest" : "alert") + " for this saved search.", items,
                total - cards.size(), properties.webBase() + "/jobs" + query(search.criteria()),
                credits(ids, sources), footer, oneClick(userId, UnsubscribeScope.SAVED_SEARCH, search.id()),
                MailComposer.sentLine(now, prefs.zone()));
        return Optional.of(new Built(ids, MailComposer.compose(layout)));
    }

    // --- pieces ---

    private Item item(JobCard card, Prefs prefs, String score, String reason, List<JobListing> sources) {
        String place = card.location() != null && !card.location().isBlank() ? card.location()
                : join(card.city(), card.country());
        if (card.workMode() != null) {
            place = place == null ? card.workMode().toLowerCase() : place + " (" + card.workMode().toLowerCase() + ")";
        }
        String salary = card.salary() == null ? null
                : MailComposer.salary(card.salary().min(), card.salary().max(), card.salary().currency(),
                        card.salary().period());
        String via = sources == null ? null
                : sources.stream().filter(l -> l.attribution() != null).findFirst()
                        .map(l -> "via " + l.attribution().name()).orElse(null);
        return new Item(card.title(), card.company() == null ? null : card.company().name(), place, salary,
                MailComposer.posted(card.postedAt(), prefs.zone()), properties.webBase() + "/jobs/" + card.id(), score,
                reason, via);
    }

    /** The credits the listed jobs' sources require, each once, in the order the jobs appear. */
    private static List<Credit> credits(List<UUID> ids, Map<UUID, List<JobListing>> sources) {
        Map<String, Credit> found = new LinkedHashMap<>();
        for (UUID id : ids) {
            for (JobListing listing : sources.getOrDefault(id, List.of())) {
                SourceAttribution a = listing.attribution();
                if (a != null) {
                    found.putIfAbsent(a.name(), new Credit(a.text() == null || a.text().isBlank() ? a.name() : a.text(), a.url()));
                }
            }
        }
        return List.copyOf(found.values());
    }

    private Link link(String label, UUID userId, UnsubscribeScope scope, UUID searchId) {
        return new Link(label, properties.webBase() + "/unsubscribe?token=" + tokens.issue(userId, scope, searchId));
    }

    private String oneClick(UUID userId, UnsubscribeScope scope, UUID searchId) {
        return properties.apiBaseUrl() + "/notifications/unsubscribe/" + tokens.issue(userId, scope, searchId);
    }

    /** The jobs page's query string for a saved search (the page's own filter names; one country at most). */
    static String query(JobSearchCriteria c) {
        List<String> parts = new ArrayList<>();
        if (c.q() != null) {
            parts.add("q=" + enc(c.q()));
        }
        c.workModes().forEach(v -> parts.add("workMode=" + enc(v)));
        c.employmentTypes().forEach(v -> parts.add("employmentType=" + enc(v)));
        c.seniorities().forEach(v -> parts.add("seniority=" + enc(v)));
        if (!c.countries().isEmpty()) {
            parts.add("country=" + enc(c.countries().get(0)));
        }
        if (c.location() != null) {
            parts.add("location=" + enc(c.location()));
        }
        if (c.minSalary() != null) {
            parts.add("minSalary=" + c.minSalary().toBigInteger());
            parts.add("salaryCurrency=" + enc(c.currency()));
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static boolean active(JobCard card) {
        return card != null && "ACTIVE".equals(card.status());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String join(String a, String b) {
        if (a != null && !a.isBlank() && b != null && !b.isBlank()) {
            return a + ", " + b;
        }
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : null;
    }

    private static String cut(String text, int max) {
        String line = MailComposer.oneLine(text);
        return line.length() <= max ? line : line.substring(0, max - 1).strip() + "…";
    }
}
