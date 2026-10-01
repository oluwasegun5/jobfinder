package com.jobfinder.core.ingestion.internal.aggregator;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;

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
 * The one place the aggregator adapters make HTTP calls, in the style of the ATS client (ADR 0020): timeouts,
 * connection errors, 408, 429 and 5xx are transient; every other non-2xx status, a redirect, an oversized body
 * and a body that is not JSON are permanent. Unlike a public job board, an aggregator's request carries a
 * credential (Adzuna's {@code app_key} is in the URL, JSearch's key in a header), so a message names only the
 * source and a label chosen by the adapter: never the URL, a header, or any of the response body, and an
 * underlying exception's text (which repeats the URL) is never copied.
 */
@Component
class AggregatorHttp {

    private final RestClient client;
    private final JsonMapper json;
    private final long maxBytes;

    AggregatorHttp(AggregatorProperties properties, JsonMapper json) {
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

    /**
     * GET {@code uri} (never expanded or re-encoded: the adapter built it) with {@code headers}, and parse the
     * body as JSON. {@code source} and {@code label} are for messages only.
     */
    JsonNode getJson(String source, String label, URI uri, Map<String, String> headers) {
        byte[] body;
        try {
            body = client.get().uri(uri).headers(h -> headers.forEach(h::set)).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status < 200 || status >= 300) {
                    throw failureFor(source, label, status);
                }
                byte[] bytes = response.getBody().readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
                if (bytes.length > maxBytes) {
                    throw SourceFetchException.permanentFailure(source + " response for " + label + " is too large",
                            null);
                }
                return bytes;
            });
        } catch (SourceFetchException e) {
            throw e;
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            // No cause attached: its message repeats the request URL.
            throw SourceFetchException.transientFailure(source + " request for " + label + " failed ("
                    + (cause == null ? e.getClass() : cause.getClass()).getSimpleName() + ")", null);
        } catch (RestClientException e) {
            throw SourceFetchException.permanentFailure(source + " request for " + label + " failed", null);
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw SourceFetchException.permanentFailure(source + " returned invalid JSON for " + label, null);
        }
    }

    private static SourceFetchException failureFor(String source, String label, int status) {
        String message = source + " request for " + label + " failed (HTTP " + status + ")";
        if (status == 429 || status == 408 || status >= 500) {
            return SourceFetchException.transientFailure(message, null);
        }
        return SourceFetchException.permanentFailure(message, null);
    }
}
