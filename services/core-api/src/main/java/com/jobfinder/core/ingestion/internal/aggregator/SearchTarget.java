package com.jobfinder.core.ingestion.internal.aggregator;

import java.util.regex.Pattern;

import com.jobfinder.core.ingestion.SourceFetchException;

/**
 * An aggregator search as a target identifier: {@code country[:query[:where]]}, for example
 * {@code gb:software engineer} or {@code za:data analyst:Cape Town}. The country is a two-letter lower-case
 * code (what Adzuna puts in its path and JSearch takes as a parameter). An identifier that does not fit is a
 * permanent failure of that one target.
 */
record SearchTarget(String country, String query, String where) {

    private static final Pattern COUNTRY = Pattern.compile("[a-z]{2}");
    private static final int MAX_PART = 100;

    static SearchTarget parse(String source, String identifier, boolean queryRequired) {
        String[] parts = identifier == null ? new String[0] : identifier.split(":", 3);
        String country = parts.length > 0 ? parts[0].trim() : "";
        if (!COUNTRY.matcher(country).matches()) {
            throw invalid(source, "must start with a two-letter lower-case country code, e.g. gb:software engineer");
        }
        String query = parts.length > 1 ? clean(source, parts[1]) : null;
        String where = parts.length > 2 ? clean(source, parts[2]) : null;
        if (queryRequired && query == null) {
            throw invalid(source, "needs a search after the country code, e.g. us:software engineer");
        }
        return new SearchTarget(country, query, where);
    }

    private static String clean(String source, String part) {
        String text = part.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_PART || text.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(source, "has a search that is too long or contains control characters");
        }
        return text;
    }

    private static SourceFetchException invalid(String source, String why) {
        return SourceFetchException.permanentFailure(source + " target " + why, null);
    }
}
