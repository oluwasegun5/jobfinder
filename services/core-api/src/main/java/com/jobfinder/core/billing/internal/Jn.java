package com.jobfinder.core.billing.internal;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Null-safe reads of a provider's JSON: a missing or wrongly typed member is simply absent. */
final class Jn {

    private Jn() {
    }

    static JsonNode at(JsonNode node, String... path) {
        JsonNode current = node;
        for (String step : path) {
            if (current == null) {
                return null;
            }
            current = current.isArray() && step.chars().allMatch(Character::isDigit)
                    ? current.path(Integer.parseInt(step)) : current.path(step);
        }
        return current == null || current.isMissingNode() || current.isNull() ? null : current;
    }

    /** A string or number member as text, else null. */
    static String text(JsonNode node, String... path) {
        JsonNode n = at(node, path);
        if (n == null || !(n.isString() || n.isNumber())) {
            return null;
        }
        String value = n.asString();
        return value == null || value.isBlank() ? null : value;
    }

    static Long number(JsonNode node, String... path) {
        JsonNode n = at(node, path);
        if (n == null) {
            return null;
        }
        if (n.isNumber()) {
            return n.asLong();
        }
        if (n.isString()) {
            try {
                return Long.parseLong(n.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Boolean bool(JsonNode node, String... path) {
        JsonNode n = at(node, path);
        return n != null && n.isBoolean() ? n.asBoolean() : null;
    }

    static Instant epochSeconds(JsonNode node, String... path) {
        Long seconds = number(node, path);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
