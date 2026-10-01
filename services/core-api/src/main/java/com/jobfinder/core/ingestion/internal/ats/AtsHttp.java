package com.jobfinder.core.ingestion.internal.ats;

import java.net.http.HttpClient;
import java.util.regex.Pattern;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.jobfinder.core.ingestion.SourceFetchException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place the ATS adapters make HTTP calls, and the one place that decides what a failure means
 * to the pipeline: timeouts, connection errors, 429, 408 and 5xx are transient (retried, counted by the
 * circuit breaker); any other non-2xx status, an oversized body and a body that is not JSON are
 * permanent (one target is wrong, not the source). Messages name the source and the board, never a
 * URL with a query, and never a response body.
 */
@Component
class AtsHttp {

    /** Board tokens and company slugs: letters, digits, dot, underscore, hyphen. Keeps them out of path/host tricks. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern HOST_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private final RestClient client;
    private final JsonMapper json;
    private final long maxBytes;

    AtsHttp(AtsProperties properties, JsonMapper json) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "JobFinder-ingestion/1.0")
                .build();
        this.json = json;
        this.maxBytes = properties.maxResponseBytes();
    }

    /** A board token or slug that is safe to put in a URL path; anything else is a permanent failure. */
    static String token(String source, String identifier) {
        if (identifier == null || !TOKEN.matcher(identifier).matches()) {
            throw SourceFetchException.permanentFailure(source + " board token is not valid", null);
        }
        return identifier;
    }

    /** A token that becomes a DNS label (Recruitee): stricter, no dots or underscores. */
    static String hostLabel(String source, String identifier) {
        if (identifier == null || !HOST_LABEL.matcher(identifier).matches()) {
            throw SourceFetchException.permanentFailure(source + " board token is not valid", null);
        }
        return identifier;
    }

    /** GET {@code url} and parse the body as JSON. {@code source} and {@code board} are for messages only. */
    JsonNode getJson(String source, String board, String url) {
        byte[] body;
        try {
            body = client.get().uri(url).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status < 200 || status >= 300) {
                    throw failureFor(source, board, status);
                }
                byte[] bytes = response.getBody().readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
                if (bytes.length > maxBytes) {
                    throw SourceFetchException.permanentFailure(
                            source + " response for " + board + " is too large", null);
                }
                return bytes;
            });
        } catch (SourceFetchException e) {
            throw e;
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            throw SourceFetchException.transientFailure(source + " request for " + board + " failed ("
                    + (cause == null ? e.getClass() : cause.getClass()).getSimpleName() + ")", e);
        } catch (RestClientException e) {
            throw SourceFetchException.permanentFailure(source + " request for " + board + " failed", e);
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw SourceFetchException.permanentFailure(source + " returned invalid JSON for " + board, e);
        }
    }

    private static SourceFetchException failureFor(String source, String board, int status) {
        String message = source + " request for " + board + " failed (HTTP " + status + ")";
        if (status == 429 || status == 408 || status >= 500) {
            return SourceFetchException.transientFailure(message, null);
        }
        return SourceFetchException.permanentFailure(message, null);
    }
}
