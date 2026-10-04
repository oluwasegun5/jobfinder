package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.billing.AiDailyCapReachedException;
import com.jobfinder.core.billing.GateStatus;
import com.jobfinder.core.billing.InsufficientCreditsException;

/**
 * The credit gate (ADR 0036): a user may start an AI call only with a balance above zero and under the daily cap;
 * Free credits are granted lazily on first use and once per calendar month (also under concurrency and when the job
 * runs twice); a grant or a top-up restores access.
 */
class CreditGateTests extends PaymentTestSupport {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private CreditLedgerStore store;

    private static Instant monthsFromNow(int months) {
        return Instant.now().atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
    }

    /** Takes the balance to exactly zero, as a spent-up user would be. */
    private void spendEverything(UUID user) {
        BigDecimal balance = store.balance(user);
        store.append(user, balance.negate(), LedgerReason.REFUND_ADJUSTMENT, null, "test:zero:" + UUID.randomUUID(),
                Instant.now());
        assertThat(balance(user)).isEqualByComparingTo("0");
    }

    @Test
    void aUserWithNoCreditsGetsATypedPaymentRequiredErrorAndATopUpRestoresAccess() throws Exception {
        UUID user = newUser();
        gate.requireAllowance(user, "parse_resume");
        spendEverything(user);

        assertThatThrownBy(() -> gate.requireAllowance(user, "parse_resume"))
                .isInstanceOfSatisfying(InsufficientCreditsException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(402);
                    assertThat(e.code()).isEqualTo("insufficient_credits");
                    assertThat(e.type()).hasToString("urn:jobfinder:problem:insufficient-credits");
                    assertThat(e.properties()).containsEntry("balance", "0");
                });
        assertThat(gate.status(user)).isEqualTo(GateStatus.INSUFFICIENT_CREDITS);

        assertThat(grants.topup(user, new BigDecimal("100"), Provider.STRIPE, "pi_gate_" + UUID.randomUUID(),
                Instant.now())).isTrue();

        assertThatCode(() -> gate.requireAllowance(user, "parse_resume")).doesNotThrowAnyException();
        assertThat(gate.status(user)).isEqualTo(GateStatus.OK);
    }

    @Test
    void aPlanGrantFromAPaymentAlsoRestoresAccess() throws Exception {
        Account account = newAccount();
        gate.requireAllowance(account.id(), "parse_resume");
        spendEverything(account.id());
        assertThatThrownBy(() -> gate.requireAllowance(account.id(), "parse_resume"))
                .isInstanceOf(InsufficientCreditsException.class);

        pending(account.id(), "STRIPE");
        stripe(stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                "sub_gate_" + UUID.randomUUID().toString().substring(0, 8), "cus_gate", account.id(), "pro",
                daysFromNow(30))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());

        assertThatCode(() -> gate.requireAllowance(account.id(), "parse_resume")).doesNotThrowAnyException();
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void anExhaustedBalanceIsReportedBeforeTheDailyCap() throws Exception {
        UUID user = newUser();
        // 600 credits used today is over the 500 cap, and more than the 300 free credits.
        ledger.record(usage(newKey(), user, "parse_resume", "0.600"));

        assertThatThrownBy(() -> gate.requireAllowance(user, "parse_resume"))
                .isInstanceOf(InsufficientCreditsException.class);
    }

    @Test
    void theDailyCapStillAppliesToAUserWithCredits() throws Exception {
        UUID user = newUser();
        grants.topup(user, new BigDecimal("5000"), Provider.PAYSTACK, "ref_cap_" + UUID.randomUUID(), Instant.now());
        ledger.record(usage(newKey(), user, "parse_resume", "0.600"));

        assertThatThrownBy(() -> gate.requireAllowance(user, "parse_resume"))
                .isInstanceOf(AiDailyCapReachedException.class);
        assertThat(gate.status(user)).isEqualTo(GateStatus.DAILY_CAP_REACHED);
    }

    @Test
    void aCallInFlightMayTakeTheBalanceBelowZeroButIsStillRecordedInFull() throws Exception {
        UUID user = newUser();
        gate.requireAllowance(user, "parse_resume");
        ledger.record(usage(newKey(), user, "parse_resume", "0.250"));
        assertThat(balance(user)).isEqualByComparingTo("50");
        // Allowed: 50 credits are left. The call then costs more than that, and its usage is recorded regardless.
        gate.requireAllowance(user, "parse_resume");
        ledger.record(usage(newKey(), user, "parse_resume", "0.200"));

        assertThat(balance(user)).isEqualByComparingTo("-150");
        assertThatThrownBy(() -> gate.requireAllowance(user, "parse_resume"))
                .isInstanceOf(InsufficientCreditsException.class);
    }

    @Test
    void anExistingUserGetsTheirFirstFreeGrantOnFirstUseAndOnlyOnce() throws Exception {
        UUID user = newUser();
        assertThat(lines(user, "PLAN_GRANT")).isZero();

        gate.requireAllowance(user, "parse_resume");
        gate.requireAllowance(user, "parse_resume");
        gate.status(user);

        assertThat(lines(user, "PLAN_GRANT")).isEqualTo(1);
        assertThat(balance(user)).isEqualByComparingTo("300");
    }

    @Test
    void aNewMonthGrantsAgainAndTheUnspentCreditsOfTheLastOneExpire() throws Exception {
        UUID user = newUser();
        Instant lastMonth = monthsFromNow(-1);
        assertThat(grants.grantFreeIfDue(user, lastMonth)).isTrue();
        ledger.record(usage(newKey(), user, "parse_resume", "0.100"), lastMonth);
        assertThat(balance(user)).isEqualByComparingTo("200");

        gate.requireAllowance(user, "parse_resume");

        assertThat(lines(user, "PLAN_GRANT")).isEqualTo(2);
        assertThat(lines(user, "PLAN_EXPIRY")).isEqualTo(1);
        assertThat(balance(user)).isEqualByComparingTo("300");
        // The ledger chain stays exact across all of it.
        assertThat(jdbc.queryForObject("""
                select count(*) from (select balance_after, lag(balance_after, 1, 0::numeric) over (order by id) + delta
                                         as expected from credit_ledger where user_id = ?) x
                 where balance_after <> expected""", Integer.class, user)).isZero();
    }

    @Test
    void theMonthlyJobRunTwiceGrantsEachUserOnce() throws Exception {
        UUID a = newUser();
        UUID b = newUser();
        UUID lazy = newUser();
        gate.requireAllowance(lazy, "parse_resume");
        Instant now = Instant.now();

        int first = jobs.grantFree(now);
        int second = jobs.grantFree(now);

        assertThat(first).isGreaterThanOrEqualTo(2);
        assertThat(second).isZero();
        for (UUID user : List.of(a, b, lazy)) {
            assertThat(lines(user, "PLAN_GRANT")).isEqualTo(1);
            assertThat(balance(user)).isEqualByComparingTo("300");
        }
    }

    @Test
    void theMonthlyJobSkipsAUserOnAPaidPlan() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        stripe(stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                "sub_job_" + UUID.randomUUID().toString().substring(0, 8), "cus_job", account.id(), "pro",
                daysFromNow(30))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk());

        jobs.grantFree(Instant.now());

        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void twoConcurrentLazyGrantsWriteTheGrantOnce() throws Exception {
        UUID user = newUser();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<GateStatus>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<GateStatus> task = () -> {
                    ready.countDown();
                    go.await();
                    return gate.status(user);
                };
                results.add(pool.submit(task));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<GateStatus> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(GateStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(lines(user, "PLAN_GRANT")).isEqualTo(1);
        assertThat(balance(user)).isEqualByComparingTo("300");
    }

    @Test
    void theLazyGrantAndTheJobRacingWriteItOnce() throws Exception {
        UUID user = newUser();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> lazy = pool.submit(() -> {
                go.await();
                return gate.status(user);
            });
            Future<?> job = pool.submit(() -> {
                go.await();
                return grants.grantFreeIfDue(user, Instant.now());
            });
            go.countDown();
            lazy.get(30, TimeUnit.SECONDS);
            job.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(lines(user, "PLAN_GRANT")).isEqualTo(1);
    }
}
