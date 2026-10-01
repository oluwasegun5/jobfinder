package com.jobfinder.core.ingestion.internal.ats;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import tools.jackson.databind.JsonNode;

/**
 * Null-tolerant reads of the loosely typed JSON the job boards return. A missing field, a JSON null or a
 * value of the wrong type all read as "absent", so one odd posting never breaks the mapping of the rest.
 * Public because the aggregator adapters read their sources' JSON the same way.
 */
public final class Fields {

    private static final DateTimeFormatter RECRUITEE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    private Fields() {
    }

    /** The string at {@code field}, trimmed; numbers are rendered as text; blank and non-scalar are null. */
    public static String text(JsonNode node, String field) {
        return scalar(node == null ? null : node.get(field));
    }

    public static String scalar(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.isString() || value.isNumber() ? value.asString() : null;
        return text == null || text.isBlank() ? null : text.trim();
    }

    public static JsonNode child(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    public static Boolean bool(JsonNode node, String field) {
        JsonNode value = child(node, field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    public static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        String text = scalar(value);
        try {
            return text == null ? null : new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ISO-8601 with an offset ("2026-09-09T10:50:29-04:00", "2024-03-04T14:29:08.532+00:00", "...Z"). */
    public static Instant isoInstant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return isoDate(text);
        }
    }

    /** A bare date ("2026-07-30") as the start of that day in UTC. */
    public static Instant isoDate(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text).atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A timestamp without an offset ("2026-09-21T12:55:11", Remotive's) read as UTC; with an offset, as stated. */
    public static Instant utcInstant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return isoInstant(text);
        }
    }

    public static Instant epochSeconds(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null || !(value.isNumber() || (value.isString() && scalar(value) != null))) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(value.asString().trim()));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    /** Recruitee's "2026-09-29 16:02:37 UTC". */
    public static Instant recruiteeInstant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, RECRUITEE_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return isoInstant(text);
        }
    }

    public static Instant epochMillis(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(value.longValue());
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** Joins the non-blank parts with {@code ", "}; null when none is left. */
    public static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(part.trim());
            }
        }
        return out.length() == 0 ? null : out.toString();
    }
}
