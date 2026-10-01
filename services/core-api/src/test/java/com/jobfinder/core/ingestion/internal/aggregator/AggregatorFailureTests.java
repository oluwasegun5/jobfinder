package com.jobfinder.core.ingestion.internal.aggregator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.tomakehurst.wiremock.http.Fault;
import com.jobfinder.core.ingestion.JobSourceAdapter;
import com.jobfinder.core.ingestion.SourceFetchException;

/**
 * What every aggregator adapter does when its source misbehaves: transient failures (408, 429, 5xx, timeouts,
 * resets) are retryable, everything else is permanent, and no message carries a URL, a query (Adzuna's key is in
 * it), a header value (JSearch's key is in one), or any of the response body.
 */
class AggregatorFailureTests extends AggregatorTestSupport {

    private static final String BODY_MARKER = "secret-body-marker";

    record Case(String name, String target, Function<AggregatorProperties, JobSourceAdapter> factory) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> adapters() {
        return Stream.of(
                new Case("ADZUNA", "gb:software engineer", p -> new AdzunaAdapter(http(p), p, JSON)),
                new Case("JSEARCH", "us:software engineer", p -> new JSearchAdapter(http(p), p, JSON)),
                new Case("REMOTIVE", "all", p -> new RemotiveAdapter(http(p), p, JSON)),
                new Case("ARBEITNOW", "all", p -> new ArbeitnowAdapter(http(p), p, JSON)),
                new Case("REMOTEOK", "all", p -> new RemoteOkAdapter(http(p), p, JSON)))
                .map(Arguments::of);
    }

    private SourceFetchException failureOf(Case adapter, AggregatorProperties properties) {
        JobSourceAdapter instance = adapter.factory().apply(properties);
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> instance.fetch(target(adapter.target()), null).toList());
        assertThat(thrown).isInstanceOf(SourceFetchException.class);
        SourceFetchException failure = (SourceFetchException) thrown;
        assertThat(failure.getMessage()).doesNotContain("localhost", "http", "?", BODY_MARKER, APP_ID, APP_KEY,
                RAPID_KEY);
        // The cause is where a URL would hide; it is never attached.
        assertThat(failure.getCause()).isNull();
        return failure;
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aNotFoundIsPermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(404).withBody(BODY_MARKER)));

        assertThat(failureOf(adapter, properties()).retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void rejectedCredentialsArePermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(401).withBody(BODY_MARKER)));
        assertThat(failureOf(adapter, properties()).retryable()).isFalse();

        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(403).withBody(BODY_MARKER)));
        assertThat(failureOf(adapter, properties()).retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void rateLimitingIsTransient(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(429).withBody(BODY_MARKER)));

        assertThat(failureOf(adapter, properties()).retryable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void requestTimeoutAndServerErrorsAreTransient(Case adapter) {
        for (int status : new int[] { 408, 500, 503 }) {
            wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(status).withBody(BODY_MARKER)));
            assertThat(failureOf(adapter, properties()).retryable()).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aDroppedConnectionIsTransient(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(failureOf(adapter, properties()).retryable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aSlowSourceTimesOutTransiently(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(okJson("{}").withFixedDelay(1500)));

        assertThat(failureOf(adapter, properties(Duration.ofMillis(300), 33_554_432L)).retryable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aBodyThatIsNotJsonIsPermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("<html>" + BODY_MARKER)));

        assertThat(failureOf(adapter, properties()).retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aResponseOfTheWrongShapeIsPermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(okJson("{\"unexpected\":\"" + BODY_MARKER + "\"}")));

        assertThat(failureOf(adapter, properties()).retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void anOversizedResponseIsPermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(okJson("{\"jobs\":[\"" + "x".repeat(4000) + "\"]}")));

        assertThat(failureOf(adapter, properties(Duration.ofSeconds(5), 1024)).retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aRedirectIsNotFollowed(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(302).withHeader("Location", "http://elsewhere.test/")));

        assertThat(failureOf(adapter, properties()).retryable()).isFalse();
    }

    @Test
    void aDroppedConnectionToAKeyedSourceNamesNeitherKeyNorUrl() {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        AdzunaAdapter adzuna = new AdzunaAdapter(http(properties()), properties(), JSON);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> adzuna.fetch(target("gb:engineer"), null).toList());

        assertThat(thrown).isInstanceOf(SourceFetchException.class);
        assertThat(thrown.getMessage()).matches("Adzuna request for gb failed \\(\\w+\\)");
    }
}
