package com.jobfinder.core.notifications.internal;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;
import com.jobfinder.core.TestcontainersConfiguration.MailpitContainer;
import com.jobfinder.core.matching.MatchService;
import com.jobfinder.core.matching.MatchingTestSupport;

/**
 * Plumbing for the notification tests: users written straight into the tables (verified, onboarded with a resume and
 * preferences), jobs scored through the real matching pipeline against the stubbed ai-service (so the score cache is what
 * production would hold), notification settings set by SQL, and Mailpit's HTTP API to read what was really sent over SMTP.
 */
abstract class NotificationsTestSupport extends MatchingTestSupport {

    static final String WEB = "http://localhost:3000";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TOKEN_IN_URL = Pattern.compile("/(?:unsubscribe\\?token=|notifications/unsubscribe/)([A-Za-z0-9_.-]+)");

    @Autowired
    protected MailpitContainer mail;

    @Autowired
    protected MatchService matchService;

    @Autowired
    protected DigestService digests;

    @Autowired
    protected InstantAlertService alerts;

    @Autowired
    protected SavedSearchService savedSearches;

    @Autowired
    protected NotificationPreferencesService preferenceService;

    /** An account: its id and unique address. */
    record Account(UUID id, String email) {
    }

    /** An email as Mailpit holds it. */
    record Message(String id, String subject, String text, String html, Map<String, List<String>> headers) {

        String header(String name) {
            List<String> values = headers.getOrDefault(name, List.of());
            return values.isEmpty() ? null : values.get(0);
        }

        /** The unsubscribe tokens found in the body's links, in order. */
        List<String> pageTokens() {
            return tokens(text);
        }

        String oneClickToken() {
            Matcher m = TOKEN_IN_URL.matcher(header("List-Unsubscribe"));
            if (!m.find()) {
                throw new AssertionError("no token in List-Unsubscribe: " + header("List-Unsubscribe"));
            }
            return m.group(1);
        }
    }

    static List<String> tokens(String body) {
        List<String> found = new ArrayList<>();
        Matcher m = TOKEN_IN_URL.matcher(body);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    // --- users ---

    /** An active user with a verified address who signed in just now. */
    protected Account verified() {
        UUID id = UUID.randomUUID();
        String email = "digest-" + id + "@example.test";
        jdbc.update("insert into users (id, email, role, status, email_verified_at, created_at, updated_at) "
                + "values (?, ?, 'USER', 'ACTIVE', now(), now(), now())", id, email);
        signedInDaysAgo(id, 0);
        return new Account(id, email);
    }

    /** A verified user with a primary resume (embedded) and saved, empty preferences: matching works for them. */
    protected Account onboarded() {
        Account a = verified();
        seedFor(a.id(), 0, "Backend engineer", "java");
        saveEmptyPreferences(a.id());
        return a;
    }

    /** Settings written straight to the table (an upsert), as if saved on the settings page. */
    protected void settings(UUID user, boolean digest, String frequency, int hour, int weekday, String timezone,
            boolean instant, int threshold) {
        jdbc.update("""
                insert into notification_preferences (user_id, email_enabled, digest_enabled, digest_frequency,
                       digest_hour, digest_weekday, timezone, instant_enabled, instant_threshold, created_at, updated_at)
                values (?, true, ?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict (user_id) do update set digest_enabled = excluded.digest_enabled,
                       digest_frequency = excluded.digest_frequency, digest_hour = excluded.digest_hour,
                       digest_weekday = excluded.digest_weekday, timezone = excluded.timezone,
                       instant_enabled = excluded.instant_enabled, instant_threshold = excluded.instant_threshold
                """, user, digest, frequency, hour, weekday, timezone, instant, threshold);
    }

    /** A daily digest at the UTC hour of {@code now}, so it is due when the run is given {@code now}. */
    protected void dailyDigestAt(UUID user, Instant now) {
        settings(user, true, "DAILY", utcHour(now), 1, null, false, 85);
    }

    protected static int utcHour(Instant at) {
        return at.atZone(java.time.ZoneOffset.UTC).getHour();
    }

    // --- jobs and scores ---

    /** A recent, embedded job close to the seeded resume, with the given title and company. */
    protected UUID strongJob(String title, String company) {
        return insert(spec().title(title).company(company).skills("java").embedding(unit(5))
                .postedAt(Instant.now().minus(1, ChronoUnit.HOURS)));
    }

    /** The model's scores for these jobs (all of them, every time), run through the real pipeline into the cache. */
    protected void score(UUID user, Map<UUID, Integer> scores) {
        stubScores(user, scores);
        matchService.rankedMatches(user);
    }

    protected static Map<UUID, Integer> scores(Object... pairs) {
        Map<UUID, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((UUID) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    // --- REST ---

    protected ResultActions postJsonAs(Session session, String path, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions putJsonAs(Session session, String path, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(path).header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // --- Mailpit ---

    protected List<Message> messages(String email) {
        try {
            String query = URLEncoder.encode("to:" + email, StandardCharsets.UTF_8);
            List<String> ids = JsonPath.read(fetch("/api/v1/search?query=" + query), "$.messages[*].ID");
            List<Message> found = new ArrayList<>();
            for (String id : ids) {
                String body = fetch("/api/v1/message/" + id);
                Map<String, List<String>> headers = JsonPath.read(fetch("/api/v1/message/" + id + "/headers"), "$");
                found.add(new Message(id, JsonPath.read(body, "$.Subject"), JsonPath.read(body, "$.Text"),
                        JsonPath.read(body, "$.HTML"), headers));
            }
            return found;
        } catch (Exception e) {
            throw new AssertionError("could not read Mailpit", e);
        }
    }

    protected List<Message> awaitMessages(String email, int count) {
        return Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150))
                .until(() -> messages(email), found -> found.size() >= count);
    }

    /** Messages are asynchronous only for the event listener; digests and polls send before they return. */
    protected int mailCount(String email) {
        return messages(email).size();
    }

    private String fetch(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(mail.apiBaseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    protected int logRows(UUID user, String kind, String status) {
        return count("select count(*) from notification_log where user_id = ? and kind = ? and status = ?", user, kind,
                status);
    }
}
