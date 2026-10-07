package com.jobfinder.core.ingestion.internal.ats;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.ingestion.SourceFetchException;
import com.jobfinder.core.shared.SsrfGuard;

/** The ATS client never connects to a target the SSRF guard refuses and never follows a redirect (ADR 0037). */
class AtsSsrfTests extends AtsTestSupport {

    @Test
    void aTargetOnTheLocalMachineIsRefusedBeforeAnyConnection() {
        wiremock.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
        AtsHttp strict = new AtsHttp(properties(), JSON, new SsrfGuard(SsrfGuard.Policy.STRICT));

        assertThatThrownBy(() -> strict.getJson("GREENHOUSE", "acme", baseUrl() + "/v1/boards/acme/jobs"))
                .isInstanceOf(SourceFetchException.class).hasMessageContaining("not allowed")
                .hasMessageNotContaining("localhost");

        assertThat(wiremock.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void theMetadataAddressIsRefusedEvenOverHttps() {
        AtsHttp strict = new AtsHttp(properties(), JSON, new SsrfGuard(SsrfGuard.Policy.STRICT));

        assertThatThrownBy(() -> strict.getJson("LEVER", "acme", "https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(SourceFetchException.class).hasMessageContaining("not allowed");
    }

    @Test
    void aRedirectToAnInternalAddressIsNotFollowed() {
        wiremock.stubFor(get(urlPathEqualTo("/v1/boards/acme/jobs")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", baseUrl() + "/internal-secret")));
        wiremock.stubFor(get(urlPathEqualTo("/internal-secret")).willReturn(aResponse().withStatus(200)
                .withBody("{\"leaked\":true}")));
        // Loopback is allowed here on purpose (WireMock), so only the redirect rule is under test.
        AtsHttp relaxed = new AtsHttp(properties(), JSON, new SsrfGuard(new SsrfGuard.Policy(true, true)));

        assertThatThrownBy(() -> relaxed.getJson("GREENHOUSE", "acme", baseUrl() + "/v1/boards/acme/jobs"))
                .isInstanceOf(SourceFetchException.class).hasMessageContaining("HTTP 302");

        assertThat(wiremock.findAll(anyRequestedFor(urlPathEqualTo("/internal-secret")))).isEmpty();
    }
}
