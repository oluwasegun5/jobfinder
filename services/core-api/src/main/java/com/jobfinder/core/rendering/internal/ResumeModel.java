package com.jobfinder.core.rendering.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * A resume reduced to what is printed, as clean strings, in the order it is printed. Both renderers read this and
 * nothing else, so the PDF and the DOCX say the same thing in the same order, and every text rule (what counts as
 * empty, how a date reads, which characters are dropped) lives in one place.
 *
 * <p>The input is the structured resume JSON (schema version 1: the shape ai-service's parser and tailoring
 * produce). It is trusted for shape only in the sense that nothing here throws on a missing or odd field: the
 * content of an approved document came from a model and a person, and a missing field is simply not printed.
 */
record ResumeModel(String name, String headline, List<String> contactLines, String summary, List<Entry> experience,
        List<Entry> education, List<String> skills, List<Entry> projects, List<Entry> certifications)
        implements Printable {

    @Override
    public String fileKind() {
        return "Resume";
    }

    /**
     * One experience, education, project or certification entry.
     *
     * @param title    the bold line: job title, degree and field, project name, certification name
     * @param detail   the second line: company and place, institution, issuer; may be empty
     * @param dates    "Mar 2021 - Present"; may be empty
     * @param text     a free paragraph (a project's description); may be empty
     * @param bullets  the bullet points, in order
     */
    record Entry(String title, String detail, String dates, String text, List<String> bullets) {
    }

    private static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec" };
    private static final Pattern DATE = Pattern.compile("(\\d{4})(?:-(\\d{2}))?");
    /** Format controls and invisible characters that have no business in a printed resume. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]");

    boolean empty() {
        return name.isEmpty() && headline.isEmpty() && contactLines.isEmpty() && summary.isEmpty()
                && experience.isEmpty() && education.isEmpty() && skills.isEmpty() && projects.isEmpty()
                && certifications.isEmpty();
    }

    static ResumeModel parse(JsonNode root) {
        JsonNode contact = root.path("contact");
        List<String> plain = new ArrayList<>();
        add(plain, text(contact, "email"));
        add(plain, text(contact, "phone"));
        add(plain, text(contact, "location"));
        List<String> links = new ArrayList<>();
        for (JsonNode link : array(contact, "links")) {
            String url = text(link, "url");
            String label = text(link, "label");
            if (!url.isEmpty()) {
                links.add(label.isEmpty() ? url : label + ": " + url);
            }
        }
        List<String> contactLines = new ArrayList<>();
        if (!plain.isEmpty()) {
            contactLines.add(String.join(" | ", plain));
        }
        if (!links.isEmpty()) {
            contactLines.add(String.join(" | ", links));
        }

        List<Entry> experience = new ArrayList<>();
        for (JsonNode job : array(root, "experience")) {
            String title = text(job, "title");
            String company = text(job, "company");
            if (title.isEmpty() && company.isEmpty()) {
                continue;
            }
            String end = Boolean.TRUE.equals(job.path("is_current").asBoolean(false)) ? "Present"
                    : date(text(job, "end_date"));
            experience.add(new Entry(title.isEmpty() ? company : title,
                    title.isEmpty() ? text(job, "location") : join(", ", company, text(job, "location")),
                    range(date(text(job, "start_date")), end), "", strings(job, "bullets", true)));
        }
        List<Entry> education = new ArrayList<>();
        for (JsonNode school : array(root, "education")) {
            String institution = text(school, "institution");
            String degree = join(", ", text(school, "degree"), text(school, "field_of_study"));
            if (institution.isEmpty() && degree.isEmpty()) {
                continue;
            }
            education.add(new Entry(degree.isEmpty() ? institution : degree, degree.isEmpty() ? "" : institution,
                    range(date(text(school, "start_date")), date(text(school, "end_date"))), "", List.of()));
        }
        List<Entry> projects = new ArrayList<>();
        for (JsonNode project : array(root, "projects")) {
            String name = text(project, "name");
            if (name.isEmpty()) {
                continue;
            }
            List<String> technologies = strings(project, "technologies", false);
            String detail = join(" | ", technologies.isEmpty() ? "" : "Technologies: " + String.join(", ", technologies),
                    text(project, "url"));
            projects.add(new Entry(name, detail, "", text(project, "description"), List.of()));
        }
        List<Entry> certifications = new ArrayList<>();
        for (JsonNode cert : array(root, "certifications")) {
            String name = text(cert, "name");
            if (name.isEmpty()) {
                continue;
            }
            certifications.add(new Entry(name, text(cert, "issuer"), date(text(cert, "date")), "", List.of()));
        }
        return new ResumeModel(text(contact, "full_name"), text(root, "headline"), List.copyOf(contactLines),
                multiline(root.path("summary")), List.copyOf(experience), List.copyOf(education),
                strings(root, "skills", false), List.copyOf(projects), List.copyOf(certifications));
    }

    // --- text rules ---

    /**
     * One line of printable text: NFC, invisible characters dropped, every kind of space and line break a single
     * space, trimmed. Never null.
     */
    static String clean(String value) {
        if (value == null) {
            return "";
        }
        String s = Normalizer.normalize(value, Normalizer.Form.NFC);
        s = INVISIBLE.matcher(s.replace(' ', ' ').replace('\t', ' ').replace('\r', ' ').replace('\n', ' '))
                .replaceAll("");
        return s.replaceAll("\\p{Zs}+", " ").strip();
    }

    /** Like {@link #clean} but keeps paragraph breaks ({@code \n} between non-empty lines). */
    static String multiline(JsonNode node) {
        if (!node.isString()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String line : node.asString().replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String cleaned = clean(line);
            if (!cleaned.isEmpty()) {
                lines.add(cleaned);
            }
        }
        return String.join("\n", lines);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? clean(value.asString()) : "";
    }

    static Iterable<JsonNode> array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isArray() ? value : List.of();
    }

    private static List<String> strings(JsonNode node, String field, boolean keepDuplicates) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array(node, field)) {
            String s = item.isString() ? clean(item.asString()) : "";
            if (!s.isEmpty() && (keepDuplicates || !out.contains(s))) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static void add(List<String> list, String value) {
        if (!value.isEmpty()) {
            list.add(value);
        }
    }

    /** Non-empty parts joined by {@code separator}. */
    static String join(String separator, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                kept.add(part);
            }
        }
        return String.join(separator, kept);
    }

    /** {@code 2021-03} reads "Mar 2021", {@code 2017} reads "2017"; anything else is printed as it is. */
    static String date(String iso) {
        var matcher = DATE.matcher(iso);
        if (!matcher.matches()) {
            return iso;
        }
        if (matcher.group(2) == null) {
            return matcher.group(1);
        }
        int month = Integer.parseInt(matcher.group(2));
        return month >= 1 && month <= 12 ? MONTHS[month - 1] + " " + matcher.group(1) : matcher.group(1);
    }

    private static String range(String start, String end) {
        if (start.isEmpty() || end.isEmpty()) {
            return start.isEmpty() ? end : start;
        }
        return start + " \u2013 " + end;
    }
}
