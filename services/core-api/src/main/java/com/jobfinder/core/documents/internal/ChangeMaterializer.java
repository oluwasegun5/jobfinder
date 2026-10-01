package com.jobfinder.core.documents.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.jobfinder.core.documents.internal.DocumentDtos.Change;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds a draft's content from the source resume and the changes: the document is always exactly the source with
 * the ACCEPTED changes applied, so rejecting a change puts the source's version of that unit back, and accepting it
 * again puts the change's {@code after} back. Nothing else is stored about the content's shape.
 *
 * <p>A change is one unit: the headline, the summary, the skills list, or one entry of experience, education,
 * projects or certifications. For an entry section the source's list is taken, REPLACE changes swap an entry (by its
 * source position), REMOVE changes drop one, and ADD changes then insert their entry at its position in the tailored
 * list (in ascending order, which reproduces the tailored list exactly when every change is accepted).
 *
 * <p>{@link Result#origin()} says which change produced each unit of the content ({@code experience[1]} to
 * {@code c3}), so a fact-check flag (which names a path in the content) can be tied to the change to reject.
 */
final class ChangeMaterializer {

    static final List<String> ENTRY_SECTIONS = List.of("experience", "education", "projects", "certifications");
    static final List<String> FIELD_SECTIONS = List.of("headline", "summary", "skills");

    private static final Pattern ENTRY_PATH = Pattern.compile("^(experience|education|projects|certifications)\\[(\\d+)]");
    private static final Pattern UNIT_PATH = Pattern
            .compile("^(?:(experience|education|projects|certifications)\\[(\\d+)]|(headline|summary|skills))$");

    private ChangeMaterializer() {
    }

    record Result(ObjectNode content, Map<String, String> origin) {

        /** The change behind a flagged path such as {@code experience[1].bullets[0]}, or null. */
        String changeFor(String path) {
            if (path == null) {
                return null;
            }
            Matcher entry = ENTRY_PATH.matcher(path);
            if (entry.find()) {
                return origin.get(entry.group(1) + "[" + entry.group(2) + "]");
            }
            for (String field : FIELD_SECTIONS) {
                if (path.equals(field) || path.startsWith(field + "[")) {
                    return origin.get(field);
                }
            }
            return null;
        }
    }

    static Result materialize(JsonNode source, List<Change> changes) {
        ObjectNode content = (ObjectNode) source.deepCopy();
        Map<String, String> origin = new HashMap<>();
        for (Change change : changes) {
            String section = change.section().toLowerCase(Locale.ROOT);
            if (FIELD_SECTIONS.contains(section) && change.accepted()) {
                content.set(section, change.after() == null ? NullNode.getInstance() : change.after().deepCopy());
                origin.put(section, change.id());
            }
        }
        for (String section : ENTRY_SECTIONS) {
            applyEntries(content, section, changes, origin);
        }
        return new Result(content, origin);
    }

    private static void applyEntries(ObjectNode content, String section, List<Change> changes,
            Map<String, String> origin) {
        JsonNode original = content.get(section);
        int size = original != null && original.isArray() ? original.size() : 0;
        List<JsonNode> values = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        boolean[] removed = new boolean[size];
        for (int i = 0; i < size; i++) {
            values.add(original.get(i));
            ids.add(null);
        }
        List<Change> additions = new ArrayList<>();
        for (Change change : changes) {
            if (!section.equalsIgnoreCase(change.section()) || !change.accepted()) {
                continue;
            }
            int index = index(change.path());
            switch (change.op()) {
                case "REPLACE" -> {
                    if (index >= 0 && index < size && change.after() != null) {
                        values.set(index, change.after());
                        ids.set(index, change.id());
                    }
                }
                case "REMOVE" -> {
                    if (index >= 0 && index < size) {
                        removed[index] = true;
                    }
                }
                case "ADD" -> additions.add(change);
                default -> {
                }
            }
        }
        List<JsonNode> result = new ArrayList<>();
        List<String> resultIds = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (!removed[i]) {
                result.add(values.get(i));
                resultIds.add(ids.get(i));
            }
        }
        additions.sort(Comparator.comparingInt(c -> index(c.path())));
        for (Change add : additions) {
            int at = Math.max(0, Math.min(index(add.path()), result.size()));
            result.add(at, add.after());
            resultIds.add(at, add.id());
        }
        ArrayNode array = content.arrayNode();
        for (int i = 0; i < result.size(); i++) {
            array.add(result.get(i).deepCopy());
            if (resultIds.get(i) != null) {
                origin.put(section + "[" + i + "]", resultIds.get(i));
            }
        }
        content.set(section, array);
    }

    /** The position in {@code experience[2]}; -1 when the path has no position. */
    static int index(String path) {
        Matcher m = ENTRY_PATH.matcher(path == null ? "" : path);
        return m.find() ? Integer.parseInt(m.group(2)) : -1;
    }

    /** The section of a unit path (as the stored change names it), or null if the path is not a unit. */
    static String sectionOf(String path) {
        Matcher m = UNIT_PATH.matcher(path == null ? "" : path);
        if (!m.matches()) {
            return null;
        }
        return (m.group(1) != null ? m.group(1) : m.group(3)).toUpperCase(Locale.ROOT);
    }

    /** The source's version of the unit a path names (for an edit of a unit that has no change yet). */
    static JsonNode unitOf(JsonNode source, String path) {
        Matcher m = UNIT_PATH.matcher(path == null ? "" : path);
        if (!m.matches()) {
            throw invalid("The path must be headline, summary, skills or an entry such as experience[0].");
        }
        if (m.group(3) != null) {
            JsonNode node = source.get(m.group(3));
            return node == null ? NullNode.getInstance() : node;
        }
        JsonNode list = source.get(m.group(1));
        int at = Integer.parseInt(m.group(2));
        if (list == null || !list.isArray() || at >= list.size()) {
            throw invalid("There is no " + m.group(1) + "[" + at + "] in the resume.");
        }
        return list.get(at);
    }

    /** Shape checks of an edited unit; the field-by-field rules are ai-service's (it parses the whole resume). */
    static void validateAfter(String section, JsonNode after) {
        switch (section) {
            case "HEADLINE" -> text(after, 200, true);
            case "SUMMARY" -> text(after, 2000, true);
            case "SKILLS" -> {
                if (after == null || !after.isArray() || after.size() > 100) {
                    throw invalid("Skills must be a list of at most 100 skills.");
                }
                for (JsonNode skill : after) {
                    if (!skill.isString() || skill.asString().isBlank() || skill.asString().length() > 100) {
                        throw invalid("Each skill must be 1 to 100 characters.");
                    }
                }
            }
            case "EXPERIENCE" -> entry(after, "company", "title");
            case "EDUCATION" -> entry(after, "institution");
            case "PROJECTS", "CERTIFICATIONS" -> entry(after, "name");
            default -> throw invalid("Unknown section.");
        }
    }

    private static void text(JsonNode value, int max, boolean nullable) {
        if (value == null || (value.isNull() && !nullable)) {
            throw invalid("A text is required.");
        }
        if (!value.isNull() && (!value.isString() || value.asString().length() > max)) {
            throw invalid("The text must be at most " + max + " characters.");
        }
    }

    private static void entry(JsonNode value, String... required) {
        if (value == null || !value.isObject()) {
            throw invalid("An entry must be an object.");
        }
        for (String field : required) {
            JsonNode v = value.get(field);
            if (v == null || !v.isString() || v.asString().isBlank()) {
                throw invalid("The entry needs a " + field + ".");
            }
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_content", detail);
    }
}
