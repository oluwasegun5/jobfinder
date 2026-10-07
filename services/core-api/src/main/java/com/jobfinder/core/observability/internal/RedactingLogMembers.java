package com.jobfinder.core.observability.internal;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import com.jobfinder.core.observability.PiiRedactor;

/**
 * Runs every string of a structured (JSON) log line, the message and the stack trace included, through
 * {@link PiiRedactor}. Switched on with {@code logging.structured.json.customizer} (application.yml), so it applies
 * wherever the JSON format is used.
 */
public class RedactingLogMembers implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, PiiRedactor::redact));
    }
}
