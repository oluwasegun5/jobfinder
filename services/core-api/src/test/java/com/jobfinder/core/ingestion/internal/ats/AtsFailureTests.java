package com.jobfinder.core.ingestion.internal.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * What every adapter does when the board misbehaves: transient failures (429, 5xx, timeouts, resets) are
 * retryable, everything else is permanent, and no message carries a URL, a token query or a response body.
 */
class AtsFailureTests extends AtsTestSupport {

    private static final String BODY_MARKER = "secret-body-marker";

    record Case(String name, Function<AtsProperties, JobSourceAdapter> factory) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> adapters() {
        return Stream.of(
                new Case("GREENHOUSE", p -> new GreenhouseAdapter(http(p), p, JSON)),
                new Case("LEVER", p -> new LeverAdapter(http(p), p, JSON)),
                new Case("ASHBY", p -> new AshbyAdapter(http(p), p, JSON)),
                new Case("WORKABLE", p -> new WorkableAdapter(http(p), p, JSON)),
                new Case("SMARTRECRUITERS", p -> new SmartRecruitersAdapter(http(p), p, JSON)),
                new Case("RECRUITEE", p -> new RecruiteeAdapter(http(p), p, JSON)))
                .map(Arguments::of);
    }

    private SourceFetchException failureOf(Case adapter, AtsProperties properties, String token) {
        JobSourceAdapter instance = adapter.factory().apply(properties);
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> instance.fetch(target(token), null).toList());
        assertThat(thrown).isInstanceOf(SourceFetchException.class);
        SourceFetchException failure = (SourceFetchException) thrown;
        assertThat(failure.getMessage()).doesNotContain("localhost", "http", "?", BODY_MARKER);
        return failure;
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void anUnknownBoardIsPermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(404).withBody(BODY_MARKER)));

        assertThat(failureOf(adapter, properties(), "nosuchboard").retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void otherClientErrorsArePermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(403).withBody(BODY_MARKER)));

        assertThat(failureOf(adapter, properties(), "someboard").retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void rateLimitingIsTransient(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(429).withBody(BODY_MARKER)));

        assertThat(failureOf(adapter, properties(), "someboard").retryable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void serverErrorsAreTransient(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(503).withBody(BODY_MARKER)));

        assertThat(failureOf(adapter, properties(), "someboard").retryable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aDroppedConnectionIsTransient(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(failureOf(adapter, properties(), "someboard").retryable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aSlowBoardTimesOutTransiently(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(okJson("{}").withFixedDelay(1500)));
        AtsProperties quick = properties(Duration.ofMillis(300), 33_554_432L, 100, 100);

        assertThat(failureOf(adapter, quick, "someboard").retryable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aBodyThatIsNotJsonIsPermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("<html>" + BODY_MARKER)));

        assertThat(failureOf(adapter, properties(), "someboard").retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aResponseOfTheWrongShapeIsPermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(okJson("{\"unexpected\":\"" + BODY_MARKER + "\"}")));

        assertThat(failureOf(adapter, properties(), "someboard").retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void anOversizedResponseIsPermanent(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(okJson("{\"jobs\":[\"" + "x".repeat(4000) + "\"]}")));
        AtsProperties small = properties(Duration.ofSeconds(5), 1024, 100, 100);

        assertThat(failureOf(adapter, small, "someboard").retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aRedirectIsNotFollowed(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(302).withHeader("Location", "http://elsewhere.test/")));

        assertThat(failureOf(adapter, properties(), "someboard").retryable()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    void aMalformedTokenIsRejectedWithoutARequest(Case adapter) {
        wiremock.stubFor(any(anyUrl()).willReturn(okJson("{}")));

        for (String token : new String[] { "../admin", "a b", "x/y", "evil.test#", "", "-lead" }) {
            assertThat(failureOf(adapter, properties(), token).retryable()).isFalse();
        }
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }

    @Test
    void aRecruiteeTokenMustBeAHostLabel() {
        RecruiteeAdapter adapter = new RecruiteeAdapter(http(properties()), properties(), JSON);

        assertThatThrownBy(() -> adapter.fetch(target("with.dots"), null)).isInstanceOf(SourceFetchException.class);
        assertThatThrownBy(() -> adapter.fetch(target("under_score"), null)).isInstanceOf(SourceFetchException.class);
    }
}
