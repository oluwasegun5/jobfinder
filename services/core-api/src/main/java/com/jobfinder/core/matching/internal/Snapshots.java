package com.jobfinder.core.matching.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.jobfinder.core.jobs.JobForMatching;
import com.jobfinder.core.profile.Candidate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the model is shown, and the hashes that say whether a cached score still describes it
 * (docs/adr/0026-matching-engine.md).
 *
 * <p>The candidate snapshot is a compact view of the primary resume plus the profile's seniority and years and the
 * preferences' target titles. It leaves out the contact block (name, e-mail, phone, links): they say nothing about
 * fit and do not belong in a prompt. The job snapshot is the job's title, company, place, work mode, type,
 * seniority, salary, skills and a bounded description. Each snapshot is built in a fixed field order, so equal
 * content gives byte-equal JSON, and its hash is the SHA-256 of that JSON: a cached score is valid only while both
 * hashes still match, which is how a resume edit or a changed job posting invalidates it.
 */
@Component
class Snapshots {

    /** The candidate as sent to ai-service, the normalized skills stage 2 compares, and the content hash. */
    record CandidateSnapshot(ObjectNode json, Set<String> skills, String hash) {
    }

    /** The job as sent to ai-service and its content hash. */
    record JobSnapshot(ObjectNode json, String hash) {
    }

    private static final int MAX_SKILLS = 100;
    private static final int MAX_ROLES = 12;
    private static final int MAX_BULLETS = 4;

    private final JsonMapper json;
    private final int descriptionChars;

    Snapshots(JsonMapper json, MatchingProperties properties) {
        this.json = json;
        this.descriptionChars = properties.llm().descriptionChars();
    }

    CandidateSnapshot candidate(Candidate candidate) {
        JsonNode resume;
        try {
            resume = json.readTree(candidate.structuredJson());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored resume content is unreadable", e);
        }
        ObjectNode out = json.createObjectNode();
        put(out, "headline", text(resume.get("headline"), 400));
        put(out, "summary", text(resume.get("summary"), 1500));
        put(out, "seniority", candidate.seniority());
        if (candidate.yearsExperience() != null) {
            out.put("years_experience", candidate.yearsExperience());
        }
        ArrayNode targets = out.putArray("target_titles");
        for (String title : candidate.preferences().targetTitles()) {
            addText(targets, title, 100, 20);
        }
        ArrayNode skills = out.putArray("skills");
        Set<String> normalized = new HashSet<>();
        for (JsonNode skill : array(resume.get("skills"))) {
            String s = text(skill, 100);
            if (s != null && skills.size() < MAX_SKILLS) {
                skills.add(s);
                normalized.add(Stage2Scorer.normalize(s));
            }
        }
        ArrayNode roles = out.putArray("experience");
        for (JsonNode role : array(resume.get("experience"))) {
            String title = text(role.get("title"), 100);
            if (title == null || roles.size() >= MAX_ROLES) {
                continue;
            }
            ObjectNode r = roles.addObject();
            r.put("title", title);
            put(r, "company", text(role.get("company"), 100));
            put(r, "period", period(role));
            ArrayNode bullets = r.putArray("bullets");
            for (JsonNode bullet : array(role.get("bullets"))) {
                if (bullets.size() < MAX_BULLETS) {
                    addText(bullets, text(bullet, 300), 300, MAX_BULLETS);
                }
            }
        }
        ArrayNode education = out.putArray("education");
        for (JsonNode school : array(resume.get("education"))) {
            String degree = join(" in ", text(school.get("degree"), 120), text(school.get("field_of_study"), 120));
            addText(education, join(", ", degree, text(school.get("institution"), 150)), 400, 8);
        }
        ArrayNode certifications = out.putArray("certifications");
        for (JsonNode cert : array(resume.get("certifications"))) {
            addText(certifications, text(cert.get("name"), 200), 400, 15);
        }
        return new CandidateSnapshot(out, normalized, sha256(out.toString()));
    }

    JobSnapshot job(JobForMatching job) {
        ObjectNode out = json.createObjectNode();
        out.put("id", job.id().toString());
        out.put("title", truncate(job.title(), 400));
        put(out, "company", text(job.company(), 400));
        put(out, "location", location(job));
        put(out, "work_mode", job.workMode());
        put(out, "employment_type", job.employmentType());
        put(out, "seniority", job.seniority());
        put(out, "salary", salary(job));
        ArrayNode skills = out.putArray("skills");
        for (String skill : job.skills()) {
            addText(skills, skill, 100, 60);
        }
        put(out, "description", text(job.descriptionText(), descriptionChars));
        return new JobSnapshot(out, sha256(out.toString()));
    }

    private static String location(JobForMatching job) {
        if (job.locationRaw() != null && !job.locationRaw().isBlank()) {
            return truncate(job.locationRaw().strip(), 400);
        }
        return join(", ", text(job.city(), 200), text(job.country(), 200));
    }

    private static String salary(JobForMatching job) {
        if (job.salaryMin() == null && job.salaryMax() == null) {
            return null;
        }
        String range = job.salaryMin() != null && job.salaryMax() != null
                && job.salaryMin().compareTo(job.salaryMax()) != 0
                        ? plain(job.salaryMin()) + "-" + plain(job.salaryMax())
                        : plain(job.salaryMax() != null ? job.salaryMax() : job.salaryMin());
        String currency = job.salaryCurrency() == null ? "" : job.salaryCurrency() + " ";
        String period = job.salaryPeriod() == null ? "" : " per " + job.salaryPeriod();
        return currency + range + period;
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String period(JsonNode role) {
        String start = text(role.get("start_date"), 10);
        String end = role.path("is_current").asBoolean(false) ? "present" : text(role.get("end_date"), 10);
        if (start == null && end == null) {
            return null;
        }
        return (start == null ? "?" : start) + " to " + (end == null ? "?" : end);
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        return node != null && node.isArray() ? node : java.util.List.of();
    }

    private static String text(JsonNode node, int max) {
        return node != null && node.isString() ? text(node.asString(), max) : null;
    }

    private static String text(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").strip();
        return cleaned.isEmpty() ? null : truncate(cleaned, max);
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end).stripTrailing();
    }

    private static String join(String separator, String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + separator + second;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static void addText(ArrayNode array, String value, int max, int limit) {
        String cleaned = text(value, max);
        if (cleaned != null && array.size() < limit) {
            array.add(cleaned);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
