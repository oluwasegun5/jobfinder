package com.jobfinder.core.rendering.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Download file names such as {@code Segun_Adeyemi_Resume_Acme.pdf}. The name of a person and the company of a job
 * are untrusted text: whatever they contain, the result is made only of ASCII letters, digits, {@code _} and
 * {@code -}, so it cannot carry a path ({@code ../}), a quote, a line break (header injection) or a control
 * character. Accents are folded (an o with a dot below and an acute becomes a plain o); a name with no Latin letters
 * at all is left out and the file is simply {@code Resume.pdf}.
 */
final class FileNames {

    private static final int MAX_PART = 30;
    private static final Map<Character, String> LETTERS = Map.ofEntries(Map.entry('ß', "ss"),
            Map.entry('ø', "o"), Map.entry('Ø', "O"), Map.entry('ł', "l"), Map.entry('Ł', "L"),
            Map.entry('đ', "d"), Map.entry('Đ', "D"), Map.entry('æ', "ae"), Map.entry('Æ', "AE"),
            Map.entry('œ', "oe"), Map.entry('Œ', "OE"), Map.entry('ð', "d"), Map.entry('Ð', "D"),
            Map.entry('þ', "th"), Map.entry('Þ', "Th"), Map.entry('ı', "i"));

    private FileNames() {
    }

    /** {@code Full_Name_Resume[_Company].ext}. */
    static String of(String fullName, String company, String extension) {
        List<String> parts = new ArrayList<>();
        String name = part(fullName);
        if (!name.isEmpty()) {
            parts.add(name);
        }
        parts.add("Resume");
        String employer = part(company);
        if (!employer.isEmpty()) {
            parts.add(employer);
        }
        return String.join("_", parts) + "." + extension.toLowerCase(Locale.ROOT);
    }

    /** Words of the text folded to [A-Za-z0-9-], joined by underscores, at most {@link #MAX_PART} characters. */
    static String part(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder folded = new StringBuilder();
        for (char c : Normalizer.normalize(text, Normalizer.Form.NFD).toCharArray()) {
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            String mapped = LETTERS.get(c);
            if (mapped != null) {
                folded.append(mapped);
            } else if (c < 128 && (Character.isLetterOrDigit(c) || c == '-')) {
                folded.append(c);
            } else {
                folded.append(' ');
            }
        }
        List<String> words = new ArrayList<>();
        for (String word : folded.toString().split(" +")) {
            String trimmed = word.replaceAll("^-+|-+$", "");
            if (!trimmed.isEmpty()) {
                words.add(trimmed);
            }
        }
        String joined = String.join("_", words);
        if (joined.length() > MAX_PART) {
            joined = joined.substring(0, MAX_PART).replaceAll("[_-]+$", "");
        }
        return joined;
    }
}
