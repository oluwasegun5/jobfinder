package com.jobfinder.core.ingestion.internal.ats;

import com.jobfinder.core.ingestion.JobSourceAdapter;
import com.jobfinder.core.ingestion.RawPosting;
import com.jobfinder.core.ingestion.SourceKind;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the six ATS adapters share: they are all {@link SourceKind#ATS}, all list everything a board
 * currently has (so {@code since} is ignored and absence counts towards expiry), and all store each
 * posting as the JSON the board returned.
 */
abstract class AtsAdapter implements JobSourceAdapter {

    private final String code;
    private final JsonMapper json;

    AtsAdapter(String code, JsonMapper json) {
        this.code = code;
        this.json = json;
    }

    @Override
    public String sourceCode() {
        return code;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.ATS;
    }

    /** A posting as stored: its own JSON, untouched. */
    protected RawPosting raw(String externalId, JsonNode posting) {
        return new RawPosting(externalId, posting.toString());
    }

    /** Re-reads a stored payload; a payload that is not a JSON object rejects the posting. */
    protected JsonNode read(RawPosting posting) {
        try {
            JsonNode node = json.readTree(posting.payload());
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(code + " posting payload is not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new IllegalArgumentException(code + " posting payload is not valid JSON", e);
        }
    }

    /** The array a board wraps its postings in; anything else is a response we cannot read. */
    protected static JsonNode requireArray(JsonNode node, String what, String source, String board) {
        if (node == null || !node.isArray()) {
            throw com.jobfinder.core.ingestion.SourceFetchException
                    .permanentFailure(source + " response for " + board + " has no " + what + " list", null);
        }
        return node;
    }
}
