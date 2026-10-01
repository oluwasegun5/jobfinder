package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import com.jobfinder.core.billing.AiCallStatus;
import com.jobfinder.core.billing.AiUsage;
import com.jobfinder.core.billing.RecordOutcome;

/** Recording usage: one call row, one debit, exact balances, idempotent and safe under concurrency. */
class LedgerServiceTests extends BillingTestSupport {

    @Autowired
    private LedgerService ledger;

    @Test
    void aUserCallIsRecordedAndDebited() throws Exception {
        UUID user = newUser();
        String key = newKey();

        // $0.0125 = 12,500 micro-dollars = 12.5 credits at 1,000 micro-dollars per credit.
        assertThat(ledger.record(usage(key, user, "parse_resume", "0.0125"))).isEqualTo(RecordOutcome.RECORDED);

        Map<String, Object> call = jdbc.queryForMap("select * from ai_calls where request_key = ?", key);
        assertThat(call).containsEntry("user_id", user).containsEntry("feature", "parse_resume")
                .containsEntry("provider", "test").containsEntry("model", "test-model")
                .containsEntry("prompt_version", "test/v1").containsEntry("pricing_version", "test-prices")
                .containsEntry("input_tokens", 100L).containsEntry("output_tokens", 20L)
                .containsEntry("cost_micro_usd", 12_500L).containsEntry("latency_ms", 5L)
                .containsEntry("status", "SUCCEEDED");
        Map<String, Object> line = jdbc.queryForMap("select * from credit_ledger where user_id = ?", user);
        assertThat((BigDecimal) line.get("delta")).isEqualByComparingTo("-12.5");
        assertThat((BigDecimal) line.get("balance_after")).isEqualByComparingTo("-12.5");
        assertThat(line).containsEntry("reason", "AI_USAGE").containsEntry("ai_call_id", call.get("id"));
    }

    @Test
    void balancesChainFromOneLineToTheNext() throws Exception {
        UUID user = newUser();

        ledger.record(usage(newKey(), user, "parse_resume", "0.010"));
        ledger.record(usage(newKey(), user, "embed_resume", "0.0025"));
        ledger.record(usage(newKey(), user, "parse_resume", "0.001"));

        List<BigDecimal> balances = jdbc.queryForList(
                "select balance_after from credit_ledger where user_id = ? order by id", BigDecimal.class, user);
        assertThat(balances).hasSize(3);
        assertThat(balances.get(0)).isEqualByComparingTo("-10");
        assertThat(balances.get(1)).isEqualByComparingTo("-12.5");
        assertThat(balances.get(2)).isEqualByComparingTo("-13.5");
    }

    @Test
    void theSameKeyIsRecordedOnceAndChargedOnce() throws Exception {
        UUID user = newUser();
        String key = newKey();

        assertThat(ledger.record(usage(key, user, "parse_resume", "0.01"))).isEqualTo(RecordOutcome.RECORDED);
        assertThat(ledger.record(usage(key, user, "parse_resume", "0.01"))).isEqualTo(RecordOutcome.DUPLICATE);
        // A redelivery that arrives with different figures still cannot change what was recorded.
        assertThat(ledger.record(usage(key, user, "parse_resume", "0.50"))).isEqualTo(RecordOutcome.DUPLICATE);

        assertThat(calls(user)).isEqualTo(1);
        assertThat(ledgerLines(user)).isEqualTo(1);
        assertThat(balance(user)).isEqualByComparingTo("-10");
    }

    @Test
    void aSystemCallIsRecordedWithoutAnOwnerOrADebit() {
        String key = newKey();

        ledger.record(usage(key, null, "embed_job", "0.01"));

        assertThat(jdbc.queryForObject("select user_id from ai_calls where request_key = ?", UUID.class, key))
                .isNull();
        assertThat(count("select count(*) from credit_ledger l join ai_calls c on c.id = l.ai_call_id "
                + "where c.request_key = ?", key)).isZero();
    }

    @Test
    void aFreeCallIsRecordedButWritesNoLedgerLine() throws Exception {
        UUID user = newUser();

        ledger.record(usage(newKey(), user, "embed_resume", "0"));

        assertThat(calls(user)).isEqualTo(1);
        assertThat(ledgerLines(user)).isZero();
    }

    @Test
    void aCallBilledByTheProviderIsRecordedEvenIfItsOutputWasDiscarded() throws Exception {
        UUID user = newUser();
        String key = newKey();

        ledger.record(new AiUsage(key, user, "parse_resume", "test", "test-model", 10, 5, new BigDecimal("0.004"),
                1, null, null, AiCallStatus.FAILED));

        assertThat(jdbc.queryForObject("select status from ai_calls where request_key = ?", String.class, key))
                .isEqualTo("FAILED");
        assertThat(balance(user)).isEqualByComparingTo("-4");
    }

    @Test
    void usageOfAnAccountThatNoLongerExistsIsStillRecordedWithoutAnOwner() {
        String key = newKey();

        assertThat(ledger.record(usage(key, UUID.randomUUID(), "parse_resume", "0.01")))
                .isEqualTo(RecordOutcome.RECORDED);

        assertThat(jdbc.queryForObject("select user_id from ai_calls where request_key = ?", UUID.class, key))
                .isNull();
    }

    @Test
    void subCreditCostsKeepTheirFractionInTheLedger() throws Exception {
        UUID user = newUser();

        // $0.0000006 rounds to 1 micro-dollar, which is 0.001 credit.
        ledger.record(usage(newKey(), user, "embed_resume", "0.0000006"));

        assertThat(balance(user)).isEqualByComparingTo("-0.001");
    }

    @Test
    void concurrentDebitsOfOneUserNeitherLoseNorDoubleCountAndTheBalanceChainStaysExact() throws Exception {
        UUID user = newUser();
        int calls = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RecordOutcome>> futures = new ArrayList<>();
        // 24 distinct calls of 2 credits each...
        for (int i = 0; i < calls; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return ledger.record(usage(newKey(), user, "parse_resume", "0.002"));
            }));
        }
        // ...plus 8 racing deliveries of one more call of 3 credits.
        String shared = newKey();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return ledger.record(usage(shared, user, "parse_resume", "0.003"));
            }));
        }
        start.countDown();
        int recorded = 0;
        for (Future<RecordOutcome> future : futures) {
            if (future.get() == RecordOutcome.RECORDED) {
                recorded++;
            }
        }
        pool.shutdown();

        assertThat(recorded).isEqualTo(calls + 1);
        assertThat(calls(user)).isEqualTo(calls + 1);
        assertThat(ledgerLines(user)).isEqualTo(calls + 1);
        assertThat(balance(user)).isEqualByComparingTo("-51");
        // Every line's balance is exactly the previous line's balance plus its own delta.
        List<Map<String, Object>> lines = jdbc.queryForList(
                "select delta, balance_after from credit_ledger where user_id = ? order by id", user);
        BigDecimal running = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            running = running.add((BigDecimal) line.get("delta"));
            assertThat((BigDecimal) line.get("balance_after")).isEqualByComparingTo(running);
        }
    }

    @Test
    void aCallCanBeDebitedOnlyOnceEvenByAWriterThatSkipsTheKeyCheck() throws Exception {
        UUID user = newUser();
        String key = newKey();
        ledger.record(usage(key, user, "parse_resume", "0.01"));
        UUID callId = jdbc.queryForObject("select id from ai_calls where request_key = ?", UUID.class, key);

        assertThatThrownBy(() -> jdbc.update("insert into credit_ledger (user_id, delta, reason, ai_call_id, "
                + "balance_after, created_at) values (?, -1, 'AI_USAGE', ?, -11, now())", user, callId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theLedgerIsAppendOnly() throws Exception {
        UUID user = newUser();
        ledger.record(usage(newKey(), user, "parse_resume", "0.01"));

        assertThatThrownBy(() -> jdbc.update("update credit_ledger set delta = 0 where user_id = ?", user))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void malformedUsageIsRefused() throws Exception {
        UUID user = newUser();

        assertThatThrownBy(() -> ledger.record(usage(" ", user, "parse_resume", "0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.record(usage(newKey(), user, "parse_resume", "-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.record(usage(newKey(), user, "", "0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls(user)).isZero();
    }

    @Test
    void usageCanBePlacedOnAnotherDay() throws Exception {
        UUID user = newUser();
        Instant yesterday = Instant.now().minusSeconds(48 * 3600);

        ledger.record(usage(newKey(), user, "parse_resume", "0.01"), yesterday);

        assertThat(jdbc.queryForObject("select created_at <= now() - interval '40 hours' from credit_ledger "
                + "where user_id = ?", Boolean.class, user)).isTrue();
    }
}
