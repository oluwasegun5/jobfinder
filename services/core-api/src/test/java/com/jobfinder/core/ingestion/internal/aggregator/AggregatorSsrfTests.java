package com.jobfinder.core.ingestion.internal.aggregator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.SourceFetchException;
import com.jobfinder.core.shared.SsrfGuard;

/** The aggregator client refuses internal targets before connecting, and a refusal never repeats the key (ADR 0037). */
class AggregatorSsrfTests extends AggregatorTestSupport {

    @Test
    void anInternalTargetIsRefusedBeforeAnyConnectionAndTheKeyIsNotInTheMessage() {
        wiremock.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
        AggregatorHttp strict = new AggregatorHttp(properties(), JSON, new SsrfGuard(SsrfGuard.Policy.STRICT));

        assertThatThrownBy(() -> strict.getJson("ADZUNA", "gb", URI.create(baseUrl() + "/v1/api/jobs?app_key=" + APP_KEY),
                Map.of("X-RapidAPI-Key", RAPID_KEY)))
                .isInstanceOf(SourceFetchException.class).hasMessageContaining("not allowed")
                .hasMessageNotContaining(APP_KEY).hasMessageNotContaining(RAPID_KEY);

        assertThat(wiremock.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void theMetadataAddressIsRefused() {
        AggregatorHttp strict = new AggregatorHttp(properties(), JSON, new SsrfGuard(SsrfGuard.Policy.STRICT));

        assertThatThrownBy(() -> strict.getJson("JSEARCH", "q", URI.create("https://169.254.169.254/latest/"), Map.of()))
                .isInstanceOf(SourceFetchException.class).hasMessageContaining("not allowed");
    }
}
