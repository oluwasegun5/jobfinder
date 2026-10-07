package com.jobfinder.core.shared;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Serialises rows to JSON for the data export. The caller passes a SELECT whose only parameter is {@code :userId} (the
 * statement is a constant in the exporting module, never built from input) and the column names to leave out (vectors,
 * storage keys, hashes: internal, not the person's data).
 */
public final class UserDataJson {

    private static final Pattern COLUMN = Pattern.compile("[a-z_][a-z0-9_]*");

    private UserDataJson() {
    }

    public static String rows(JdbcClient jdbc, String select, UUID userId, String... omit) {
        for (String column : omit) {
            if (!COLUMN.matcher(column).matches()) {
                throw new IllegalArgumentException("not a column name: " + column);
            }
        }
        String omitted = omit.length == 0 ? ""
                : " - array[" + String.join(",", Arrays.stream(omit).map(c -> "'" + c + "'").toList()) + "]";
        return jdbc.sql("select coalesce(jsonb_pretty(jsonb_agg(to_jsonb(t)" + omitted
                + ")), '[]') from (" + select + ") t")
                .param("userId", userId).query(String.class).single();
    }
}
