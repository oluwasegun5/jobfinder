package com.jobfinder.core.profile.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Normalises free text that users (or the CV parser) supply, before it is validated and stored. Control characters
 * become spaces (a NUL would also make Postgres reject the JSON), and surrounding whitespace goes. Applied in the
 * request records' constructors so validation always sees the cleaned value.
 */
final class CleanText {

    private CleanText() {
    }

    /** Cleaned text, or null when nothing is left. */
    static String orNull(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Cleaned text, or an empty string. Use for required fields so that validation reports them as blank. */
    static String orEmpty(String value) {
        String cleaned = orNull(value);
        return cleaned == null ? "" : cleaned;
    }

    /** Cleaned entries with blanks and case-insensitive duplicates removed, order kept; never null. */
    static List<String> list(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String cleaned = orNull(value);
            if (cleaned != null && seen.add(cleaned.toLowerCase(Locale.ROOT))) {
                result.add(cleaned);
            }
        }
        return List.copyOf(result);
    }

    /** Like {@link #list} but keeps duplicates (bullets may legitimately repeat). */
    static List<String> listKeepingDuplicates(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(CleanText::orNull).filter(Objects::nonNull).toList();
    }

    static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
