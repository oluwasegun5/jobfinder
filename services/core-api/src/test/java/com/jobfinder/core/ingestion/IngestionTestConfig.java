package com.jobfinder.core.ingestion;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Three fake sources, each with its own code so its fault-tolerance state (retry exhaustion, the
 * circuit breaker) cannot leak into the other tests: FAKE for ordinary runs, FAKE_RETRY for the
 * test that exhausts retries, FAKE_BREAKER for the one that trips the breaker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IngestionTestConfig {

    @Bean
    FakeJobSourceAdapter fakeSource() {
        return new FakeJobSourceAdapter("FAKE", SourceKind.ATS);
    }

    @Bean
    FakeJobSourceAdapter fakeRetrySource() {
        return new FakeJobSourceAdapter("FAKE_RETRY", SourceKind.ATS);
    }

    @Bean
    FakeJobSourceAdapter fakeBreakerSource() {
        return new FakeJobSourceAdapter("FAKE_BREAKER", SourceKind.AGGREGATOR);
    }
}
