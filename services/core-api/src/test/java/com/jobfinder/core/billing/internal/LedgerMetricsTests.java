package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.micrometer.core.instrument.MeterRegistry;

/** PLAN.md section 11: AI cost per feature and credits consumed are counted once per recorded call. */
class LedgerMetricsTests extends BillingTestSupport {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private MeterRegistry meters;

    private static String feature() {
        return "metrics_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private double cost(String feature) {
        return meters.counter("ai.cost.usd", "feature", feature, "model", "test-model").count();
    }

    @Test
    void costCallsAndCreditsAreCountedPerFeature() throws Exception {
        String feature = feature();
        UUID user = newUser();

        ledger.record(usage(newKey(), user, feature, "0.0125"));
        ledger.record(usage(newKey(), user, feature, "0.0075"));

        assertThat(cost(feature)).isEqualTo(0.02);
        assertThat(meters.counter("ai.calls", "feature", feature, "status", "SUCCEEDED").count()).isEqualTo(2.0);
        // 1,000 micro-dollars a credit: $0.02 is 20 credits.
        assertThat(meters.counter("credits.consumed", "feature", feature).count()).isEqualTo(20.0);
        assertThat(meters.timer("ai.call.duration", "feature", feature).count()).isEqualTo(2L);
    }

    @Test
    void aDuplicateDeliveryIsNotCountedTwice() throws Exception {
        String feature = feature();
        UUID user = newUser();
        String key = newKey();

        ledger.record(usage(key, user, feature, "0.01"));
        ledger.record(usage(key, user, feature, "0.01"));

        assertThat(cost(feature)).isEqualTo(0.01);
        assertThat(meters.counter("ai.calls", "feature", feature, "status", "SUCCEEDED").count()).isEqualTo(1.0);
    }

    @Test
    void aSystemCallCostsMoneyButConsumesNoCredits() {
        String feature = feature();

        ledger.record(usage(newKey(), null, feature, "0.01"));

        assertThat(cost(feature)).isEqualTo(0.01);
        assertThat(meters.counter("credits.consumed", "feature", feature).count()).isZero();
    }
}
