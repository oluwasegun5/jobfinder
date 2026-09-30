package com.jobfinder.core.profile.internal;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why a CV could not be parsed. {@link #code()} is what {@code resumes.parse_error} stores and
 * the API returns; only codes listed here are ever stored, whatever the parser service says.
 */
enum ParseFailureReason {
    UNSUPPORTED_FILE_TYPE("unsupported_file_type", true),
    FILE_TOO_LARGE("file_too_large", true),
    EMPTY_FILE("empty_file", true),
    UNREADABLE_FILE("unreadable_file", true),
    NO_EXTRACTABLE_TEXT("no_extractable_text", true),
    LLM_REFUSED("llm_refused", true),
    LLM_OUTPUT_INVALID("llm_output_invalid", true),
    /** ai-service, the LLM or object storage stayed unreachable after every retry (or is misconfigured). */
    PARSER_UNAVAILABLE("parser_unavailable", false),
    PARSER_ERROR("parser_error", false),
    INVALID_PARSER_RESPONSE("invalid_parser_response", false),
    FILE_MISSING("file_missing", false),
    QUEUE_UNAVAILABLE("parse_queue_unavailable", false),
    UNEXPECTED_ERROR("unexpected_error", false);

    private final String code;
    private final boolean reportedByParser;

    ParseFailureReason(String code, boolean reportedByParser) {
        this.code = code;
        this.reportedByParser = reportedByParser;
    }

    String code() {
        return code;
    }

    /** Maps a {@code code} from an ai-service problem response; unknown codes are not trusted. */
    static Optional<ParseFailureReason> fromParserCode(String code) {
        return Arrays.stream(values()).filter(r -> r.reportedByParser && r.code.equals(code)).findFirst();
    }
}
