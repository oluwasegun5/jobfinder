package com.jobfinder.core.embeddings.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Builds the text that is embedded, and the hash that says whether an embedding is still current.
 *
 * <p>Jobs embed their title, company and description. Resumes embed what a recruiter would match on (headline,
 * summary, roles with their bullets, skills, education, projects, certifications) and never the contact block: a
 * name, e-mail address or phone number says nothing about fit and does not belong in a vector.
 *
 * <p>Both texts are cut to {@code maxInputChars} (about a quarter of that in tokens) so one batch always fits the
 * provider's per-request limit. {@link #TEMPLATE_VERSION} is part of every hash: changing the templates, the
 * truncation policy or the provider input type means bumping it, which makes every stored embedding stale and
 * lets the backfill re-embed them.
 */
@Component
class EmbeddingTextBuilder {

    static final String TEMPLATE_VERSION = "v1";

    private final int maxChars;

    EmbeddingTextBuilder(EmbeddingProperties properties) {
        this.maxChars = properties.maxInputChars();
    }

    String job(String title, String company, String descriptionText) {
        StringBuilder text = new StringBuilder();
        line(text, "Title", title);
        line(text, "Company", company);
        if (descriptionText != null && !descriptionText.isBlank()) {
            text.append('\n').append(descriptionText.strip());
        }
        return truncate(text.toString().strip());
    }

    /** {@code structured} is the parsed JSON of {@code resume_versions.structured}; unknown shapes yield empty text. */
    String resume(Map<?, ?> structured) {
        StringBuilder text = new StringBuilder();
        line(text, "Headline", string(structured.get("headline")));
        line(text, "Summary", string(structured.get("summary")));
        for (Map<?, ?> job : maps(structured.get("experience"))) {
            String role = join(" at ", string(job.get("title")), string(job.get("company")));
            line(text, "Experience", role);
            for (Object bullet : list(job.get("bullets"))) {
                line(text, "-", string(bullet));
            }
        }
        List<String> skills = list(structured.get("skills")).stream().map(EmbeddingTextBuilder::string)
                .filter(s -> s != null).toList();
        if (!skills.isEmpty()) {
            line(text, "Skills", String.join(", ", skills));
        }
        for (Map<?, ?> school : maps(structured.get("education"))) {
            String degree = join(" in ", string(school.get("degree")), string(school.get("field_of_study")));
            line(text, "Education", join(", ", degree, string(school.get("institution"))));
        }
        for (Map<?, ?> project : maps(structured.get("projects"))) {
            line(text, "Project", join(": ", string(project.get("name")), string(project.get("description"))));
        }
        for (Map<?, ?> cert : maps(structured.get("certifications"))) {
            line(text, "Certification", join(", ", string(cert.get("name")), string(cert.get("issuer"))));
        }
        return truncate(text.toString().strip());
    }

    /** Hex SHA-256 of the template version and the text. */
    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((TEMPLATE_VERSION + "\n" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Cuts to the budget, preferably at whitespace, never inside a surrogate pair. */
    private String truncate(String text) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        int space = text.lastIndexOf(' ', end);
        if (space > end - 200 && space > 0) {
            end = space;
        }
        return text.substring(0, end).stripTrailing();
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(label.equals("-") ? " " : ": ").append(value.strip()).append('\n');
        }
    }

    private static String join(String separator, String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return second == null || second.isBlank() ? first : first + separator + second;
    }

    private static String string(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    private static List<Map<?, ?>> maps(Object value) {
        return list(value).stream().filter(Map.class::isInstance).<Map<?, ?>>map(Map.class::cast).toList();
    }
}
