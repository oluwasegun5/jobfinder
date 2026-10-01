package com.jobfinder.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Retries and the circuit breaker, which protect a source (and us) from a source that is unwell. */
class IngestionFaultToleranceTests extends IngestionTestSupport {

    @Test
    void aRetryableFailureIsRetriedUntilTheTargetSucceeds() {
        addTarget(FAKE, "flaky");
        fake.failRetryablyThenPostings("flaky", 2, 3);

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(fake.callsFor("flaky")).as("two failures, then the third attempt works").isEqualTo(3);
        assertThat(summary.status()).isEqualTo(IngestionRunStatus.SUCCEEDED);
        assertThat(summary.fetched()).isEqualTo(3);
        assertThat(summary.errors()).isZero();
    }

    @Test
    void aRetryableFailureThatNeverClearsFailsTheTargetAfterTheLastAttempt() {
        addTarget(FAKE_RETRY, "down");
        fakeRetry.failRetryablyAlways("down");

        IngestionRunSummary summary = ingestion.runNow(FAKE_RETRY).orElseThrow();

        assertThat(fakeRetry.callsFor("down")).as("three attempts in total").isEqualTo(3);
        assertThat(summary.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(summary.errors()).isEqualTo(1);
    }

    @Test
    void aSourceThatKeepsFailingTripsItsCircuitBreakerAndLaterTargetsAreSkipped() {
        // One attempt per target, so each failing target is exactly one call against the breaker, which
        // opens after three failures (breaker-minimum-calls=3 in the test configuration).
        jdbc.update("update sources set config = '{\"retryMaxAttempts\": 1}'::jsonb where code = 'FAKE_BREAKER'");
        for (int i = 1; i <= 6; i++) {
            addTarget(FAKE_BREAKER, "board-" + i);
            fakeBreaker.failRetryablyAlways("board-" + i);
        }

        IngestionRunSummary summary = ingestion.runNow(FAKE_BREAKER).orElseThrow();

        assertThat(fakeBreaker.calls()).as("the breaker opened after three failures: the rest never reached "
                + "the source").hasSize(3);
        assertThat(summary.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(summary.errors()).as("every target is still accounted for").isEqualTo(6);
        assertThat(jdbc.queryForObject("select error_summary from ingestion_runs where id = ?", String.class,
                summary.runId())).contains("CallNotPermittedException");
    }

    @Test
    void aPermanentFailureNeverCountsAgainstTheCircuitBreaker() {
        for (int i = 1; i <= 6; i++) {
            addTarget(FAKE, "retired-" + i);
            fake.failPermanently("retired-" + i);
        }
        addTarget(FAKE, "zz-healthy");
        fake.postings("zz-healthy", 2);

        IngestionRunSummary summary = ingestion.runNow(FAKE).orElseThrow();

        assertThat(summary.errors()).isEqualTo(6);
        assertThat(summary.fetched()).as("six dead boards did not stop the healthy one").isEqualTo(2);
        assertThat(summary.status()).isEqualTo(IngestionRunStatus.PARTIAL);
        assertThat(fake.callsFor("zz-healthy")).isEqualTo(1);
    }
}
