package com.jobfinder.core.applications.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the URL of an application form, or a job's stored apply link, into one canonical string so the two can be
 * compared (docs/adr/0035-chrome-extension.md). The page URL is the only thing the extension sends about the page, and it
 * is untrusted: it is only parsed, compared and used as a literal search fragment, never fetched or followed.
 *
 * <p>What is dropped: the fragment, user info, tracking parameters, and (for the four ATS the extension knows) everything
 * that is not the job's identity: the apply suffix of a Lever, Ashby or Workday page, a Workday locale segment, and the
 * query. What is kept: the ATS ids that identify a job on a company's own domain ({@code gh_jid}, {@code ashby_jid}) and any
 * other parameter, sorted, so two links to different jobs on a generic site never collapse into one.
 */
final class ApplyUrls {

    static final int MAX_LENGTH = 2000;

    private static final Set<String> TRACKING = Set.of("gclid", "fbclid", "msclkid", "dclid", "yclid", "mc_cid",
            "mc_eid", "ref", "referrer", "referral", "source", "src", "gh_src", "lever-source", "lever-origin",
            "lever-source[]", "trk", "trkcampaign", "tracking", "tracking_id", "trackingid", "campaign", "cid",
            "igshid", "_hsenc", "_hsmi", "s_cid", "icid", "vero_id", "li_fat_id", "__s", "ashby_src");
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}([-_][a-zA-Z]{2})?$");
    private static final Pattern WORKDAY_HOST = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)*\\.myworkdayjobs\\.com$");
    private static final Set<String> GREENHOUSE_HOSTS = Set.of("boards.greenhouse.io", "job-boards.greenhouse.io",
            "boards.eu.greenhouse.io", "job-boards.eu.greenhouse.io");
    private static final Set<String> LEVER_HOSTS = Set.of("jobs.lever.co", "jobs.eu.lever.co");

    private ApplyUrls() {
    }

    /** The canonical form, or empty when {@code raw} is not an http(s) URL with a host. */
    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        List<String> segments = segments(uri.getRawPath());
        String query = uri.getRawQuery();

        if (GREENHOUSE_HOSTS.contains(host)) {
            return Optional.of(greenhouse(host, segments, query));
        }
        if (LEVER_HOSTS.contains(host)) {
            return Optional.of(firstTwo("https://" + host, segments));
        }
        if (host.equals("jobs.ashbyhq.com")) {
            return Optional.of(firstTwo("https://" + host, segments));
        }
        if (WORKDAY_HOST.matcher(host).matches()) {
            return Optional.of(workday(host, segments));
        }
        return Optional.of(generic(scheme, host, port, segments, query));
    }

    /**
     * The text to search stored apply links for before comparing them properly: the most specific identifier in the
     * canonical URL (an ATS job id, or the last path segment), or the host when there is nothing more.
     */
    static String searchFragment(String canonical) {
        URI uri = URI.create(canonical);
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String lower = pair.toLowerCase(Locale.ROOT);
                if (lower.startsWith("gh_jid=") || lower.startsWith("ashby_jid=")) {
                    return pair.substring(pair.indexOf('=') + 1);
                }
            }
        }
        List<String> segments = segments(uri.getRawPath());
        if (!segments.isEmpty()) {
            return segments.get(segments.size() - 1);
        }
        return uri.getHost();
    }

    private static String greenhouse(String host, List<String> segments, String query) {
        String canonicalHost = host.replace("job-boards.", "boards.");
        String lower;
        // The embedded form: /embed/job_app?for=<board>&token=<job id>.
        if (segments.size() >= 2 && segments.get(0).equalsIgnoreCase("embed")
                && segments.get(1).equalsIgnoreCase("job_app")) {
            String board = param(query, "for");
            String token = param(query, "token");
            if (board != null && token != null) {
                return ("https://" + canonicalHost + "/" + board + "/jobs/" + token).toLowerCase(Locale.ROOT);
            }
        }
        // /<board>/jobs/<id>, whatever follows.
        if (segments.size() >= 3 && segments.get(1).equalsIgnoreCase("jobs")) {
            lower = "https://" + canonicalHost + "/" + String.join("/", segments.subList(0, 3));
            return lower.toLowerCase(Locale.ROOT);
        }
        return ("https://" + canonicalHost + (segments.isEmpty() ? "" : "/" + String.join("/", segments)))
                .toLowerCase(Locale.ROOT);
    }

    private static String firstTwo(String origin, List<String> segments) {
        List<String> keep = segments.subList(0, Math.min(2, segments.size()));
        return (origin + (keep.isEmpty() ? "" : "/" + String.join("/", keep))).toLowerCase(Locale.ROOT);
    }

    private static String workday(String host, List<String> segments) {
        List<String> path = new ArrayList<>(segments);
        if (!path.isEmpty() && LOCALE.matcher(path.get(0)).matches()) {
            path.remove(0);
        }
        int apply = -1;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).equalsIgnoreCase("apply")) {
                apply = i;
                break;
            }
        }
        if (apply >= 0) {
            path = path.subList(0, apply);
        }
        return "https://" + host + (path.isEmpty() ? "" : "/" + String.join("/", path));
    }

    private static String generic(String scheme, String host, int port, List<String> segments, String query) {
        StringBuilder out = new StringBuilder(scheme).append("://").append(host);
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443)
                || (scheme.equals("http") && port == 80);
        if (!defaultPort) {
            out.append(':').append(port);
        }
        if (!segments.isEmpty()) {
            out.append('/').append(String.join("/", segments));
        }
        String kept = keptQuery(query);
        if (!kept.isEmpty()) {
            out.append('?').append(kept);
        }
        return out.toString();
    }

    private static String keptQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return Arrays.stream(query.split("&")).filter(p -> !p.isEmpty()).filter(p -> !tracking(name(p)))
                .sorted(Comparator.comparing((String p) -> name(p).toLowerCase(Locale.ROOT)).thenComparing(p -> p))
                .reduce((a, b) -> a + "&" + b).orElse("");
    }

    private static boolean tracking(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("utm_") || TRACKING.contains(lower);
    }

    private static String name(String pair) {
        int eq = pair.indexOf('=');
        return eq < 0 ? pair : pair.substring(0, eq);
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (name(pair).equalsIgnoreCase(key)) {
                int eq = pair.indexOf('=');
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private static List<String> segments(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(rawPath.split("/")).filter(s -> !s.isEmpty()).toList();
    }
}
