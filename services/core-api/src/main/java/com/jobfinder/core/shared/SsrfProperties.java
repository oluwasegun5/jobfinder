package com.jobfinder.core.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.security.ssrf}. Both switches default to the strict production behaviour and exist so the test suite can
 * point the ingestion clients at WireMock on localhost; nothing in a deployed profile sets them.
 */
@ConfigurationProperties("app.security.ssrf")
public record SsrfProperties(@DefaultValue("false") boolean allowHttp,
        @DefaultValue("false") boolean allowPrivateAddresses) {
}
