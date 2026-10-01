package com.jobfinder.core.ingestion.internal.aggregator;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jobfinder.core.ingestion.JobSourceAdapter;
import com.jobfinder.core.ingestion.RawPosting;
import com.jobfinder.core.ingestion.SourceAttribution;
import com.jobfinder.core.ingestion.SourceFetchException;
import com.jobfinder.core.ingestion.SourceKind;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the five aggregator adapters share: they are {@link SourceKind#AGGREGATOR}, none is a
 * {@code fullListing()} (each fetch is a capped slice of search results, so a posting's absence says nothing:
 * expiry is the 45-day rule of ADR 0019), each stores a posting as the JSON the source returned, each carries
 * the credit its terms require, and every request goes through the source's {@link RequestGate}.
 */
abstract class AggregatorAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(AggregatorAdapter.class);
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,59}");

    private final String code;
    private final String displayName;
    private final JsonMapper json;
    private final AggregatorHttp http;
    private final RequestGate gate;
    private final SourceAttribution attribution;

    AggregatorAdapter(String code, String displayName, JsonMapper json, AggregatorHttp http, int maxRequestsPerDay,
            Duration minRequestInterval, SourceAttribution attribution) {
        this.code = code;
        this.displayName = displayName;
        this.json = json;
        this.http = http;
        this.gate = new RequestGate(displayName, maxRequestsPerDay, minRequestInterval, Clock.systemUTC());
        this.attribution = attribution;
    }

    @Override
    public String sourceCode() {
        return code;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.AGGREGATOR;
    }

    @Override
    public boolean fullListing() {
        return false;
    }

    @Override
    public Optional<SourceAttribution> attribution() {
        return Optional.of(attribution);
    }

    String displayName() {
        return displayName;
    }

    /**
     * One request, after the quota gate. {@code first} says whether this is the target's first page: when the
     * day's budget is spent, a first page fails the target (with the reason in the run record, and without
     * counting against the circuit breaker), while a later page just ends the paging and keeps what the earlier
     * pages returned.
     *
     * @return the parsed body, or null when a later page was refused for want of budget
     */
    protected JsonNode request(String label, URI uri, Map<String, String> headers, boolean first) {
        if (!gate.tryAcquire()) {
            if (first) {
                throw SourceFetchException.permanentFailure(displayName + " daily request budget (" + gate.perDay()
                        + ") is used up; it resets at 00:00 UTC", null);
            }
            log.warn("Source {}: daily request budget ({}) used up, stopping this target early", code,
                    gate.perDay());
            return null;
        }
        return http.getJson(displayName, label, uri, headers);
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

    /** The array a source wraps its postings in; anything else is a response we cannot read. */
    protected JsonNode requireArray(JsonNode node, String what, String label) {
        if (node == null || !node.isArray()) {
            throw SourceFetchException.permanentFailure(
                    displayName + " response for " + label + " has no " + what + " list", null);
        }
        return node;
    }

    /** Postings by external id, in arrival order: a posting that shifts between pages is kept once. */
    protected static Map<String, RawPosting> collector() {
        return new LinkedHashMap<>();
    }

    protected static void add(Map<String, RawPosting> into, RawPosting posting) {
        into.putIfAbsent(posting.externalId(), posting);
    }

    /** A category or tag slug, which becomes a query value; anything else is a permanent failure of the target. */
    protected String slug(String identifier) {
        if (identifier == null || !SLUG.matcher(identifier).matches()) {
            throw SourceFetchException.permanentFailure(displayName + " target is not valid", null);
        }
        return identifier;
    }

    /** {@code base + path} with the query parameters percent-encoded (a space is %20, a plus sign %2B). */
    protected static URI uri(String base, String path, Map<String, String> query) {
        String text = query.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(base + path + (text.isEmpty() ? "" : "?" + text));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
