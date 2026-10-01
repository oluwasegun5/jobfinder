package com.jobfinder.core.ingestion.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.jobfinder.core.ingestion.internal.NormalizedJob.WorkMode;

/**
 * The comparison forms that deduplication is built on (PLAN.md section 6): company, title and
 * location reduced to what identifies a job rather than how one source happens to write it, and the
 * fingerprint, a SHA-256 of the three. The forms are aggressive on purpose about decoration (legal
 * suffixes, "(m/f/d)", "- Remote", requisition numbers) and careful about content: "Senior" and
 * "Junior" stay, so two levels of the same role are two jobs.
 */
final class Names {

    private static final Set<String> LEGAL_SUFFIXES = Set.of("inc", "incorporated", "llc", "ltd", "limited", "gmbh",
            "ag", "plc", "corp", "corporation", "co", "company", "sa", "bv", "nv", "pty", "pvt", "llp", "lp", "srl",
            "sas");
    private static final Pattern GENDER = Pattern.compile(
            "\\(?\\b[mfwdx]\\s*[/\\\\|]\\s*[mfwdx](\\s*[/\\\\|]\\s*[mfwdx])?\\b\\)?|\\(?\\ball genders\\)?|\\(?\\bgn\\b\\)?");
    private static final Pattern WORK_MODE = Pattern.compile(
            "\\b(remote(ly)?|hybrid|on[- ]?site|in[- ]office|work from home|wfh)\\b");
    private static final Pattern REFERENCE = Pattern.compile(
            "#\\s?\\d+|\\b(req|requisition|ref|job id|id)[\\s#:.-]*[a-z]*\\d+\\b|\\b[rj]r?-?\\d{4,}\\b");
    private static final Pattern BRACKETS = Pattern.compile("[(\\[{]\\s*[)\\]}]");
    private static final Set<String> DANGLING = Set.of("in", "at", "for", "from", "based", "and", "or", "to", "with");

    private Names() {
    }

    /** "Acme, Inc." and "ACME Corp" and "The Acme Company" are all "acme". */
    static String company(String name) {
        String text = TextCleaner.fold(name).replace("&", " and ").replaceAll("\\([^)]*\\)", " ");
        List<String> tokens = tokens(text.replaceAll("[^a-z0-9]+", " "));
        if (!tokens.isEmpty() && tokens.get(0).equals("the") && tokens.size() > 1) {
            tokens.remove(0);
        }
        while (tokens.size() > 1 && LEGAL_SUFFIXES.contains(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return String.join(" ", tokens);
    }

    /**
     * "Sr. Software Engineer (m/f/d) - Remote [REQ-1042]" becomes "senior software engineer". When the
     * city is known, a trailing "in Lagos" is dropped too.
     */
    static String title(String title, String city) {
        String text = TextCleaner.fold(title).replace("&", " and ");
        text = GENDER.matcher(text).replaceAll(" ");
        text = REFERENCE.matcher(text).replaceAll(" ");
        text = WORK_MODE.matcher(text).replaceAll(" ");
        text = BRACKETS.matcher(text).replaceAll(" ");
        List<String> tokens = tokens(text.replaceAll("[^a-z0-9+#]+", " "));
        for (int i = 0; i < tokens.size(); i++) {
            switch (tokens.get(i)) {
                case "sr", "snr" -> tokens.set(i, "senior");
                case "jr" -> tokens.set(i, "junior");
                default -> {
                }
            }
        }
        if (city != null) {
            List<String> cityTokens = tokens(TextCleaner.fold(city).replaceAll("[^a-z0-9]+", " "));
            if (tokens.size() > cityTokens.size() && tokens.subList(tokens.size() - cityTokens.size(), tokens.size())
                    .equals(cityTokens)) {
                tokens = new ArrayList<>(tokens.subList(0, tokens.size() - cityTokens.size()));
            }
        }
        while (tokens.size() > 1 && DANGLING.contains(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return String.join(" ", tokens);
    }

    /**
     * Where the job is, as a comparison key: "lagos|NG", "remote|US", "US", "remote" or "". A remote
     * job is placed by country only, so "Remote - US" and "Remote (United States)" meet.
     */
    static String location(LocationParser.Parsed location, WorkMode workMode) {
        String country = location.country() == null ? "" : location.country();
        if (location.city() != null) {
            return TextCleaner.fold(location.city()).replaceAll("\\s+", " ").trim() + "|" + country;
        }
        if (workMode == WorkMode.REMOTE) {
            return country.isEmpty() ? "remote" : "remote|" + country;
        }
        return country;
    }

    static String fingerprint(String normalizedCompany, String normalizedTitle, String locationKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = normalizedCompany + "\n" + normalizedTitle + "\n" + locationKey;
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
