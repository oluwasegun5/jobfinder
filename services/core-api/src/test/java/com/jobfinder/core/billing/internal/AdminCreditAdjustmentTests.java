package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jobfinder.core.billing.CreditAdjustments;

/**
 * The manual refund flow (docs/adr/0036-plans-credits-and-payments.md, refund addendum): an admin takes credits back
 * from a user with one {@code REFUND_ADJUSTMENT} ledger line, idempotent on the key the admin supplies. Admin only; the
 * authorization of every {@code /admin/**} mapping is also asserted by AdminAndInternalAccessGuardTests.
 */
class AdminCreditAdjustmentTests extends PaymentTestSupport {

    @Autowired
    private CreditAdjustments adjustments;

    private String adminToken() throws Exception {
        String email = registerVerifiedUser();
        jdbc.update("update users set role = 'ADMIN' where email = ?", email);
        return login(email, PASSWORD, newIp()).accessToken();
    }

    private ResultActions adjust(String bearer, UUID user, String key, String delta, String reason) throws Exception {
        var request = post("/admin/billing/users/{id}/adjustments", user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":" + quote(key) + ",\"delta\":" + delta + ",\"reason\":" + quote(reason)
                        + "}");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private static String quote(String text) {
        return text == null ? "null" : "\"" + text.replace("\"", "\\\"") + "\"";
    }

    private static String key() {
        return "re_" + UUID.randomUUID().toString().substring(0, 12);
    }

    /** A user holding 2,000 credits from a purchased pack (so the line also shows what happens to the top-up part). */
    private Account userWithTopup() throws Exception {
        Account account = newAccount();
        stripe(stripePackCheckout(newEventId(), Instant.now(), "pi_adj_" + UUID.randomUUID().toString().substring(0, 8),
                account.id(), "pack_small")).andExpect(status().isOk());
        assertThat(balance(account.id())).isEqualByComparingTo("2000");
        return account;
    }

    @Test
    void onlyAnAdminMayAdjust() throws Exception {
        Account user = userWithTopup();

        adjust(null, user.id(), key(), "-100", "refund").andExpect(status().isUnauthorized());
        adjust(user.token(), user.id(), key(), "-100", "refund").andExpect(status().isForbidden());
        assertThat(lines(user.id(), "REFUND_ADJUSTMENT")).isZero();

        adjust(adminToken(), user.id(), key(), "-100", "refund").andExpect(status().isCreated());
        assertThat(lines(user.id(), "REFUND_ADJUSTMENT")).isEqualTo(1);
    }

    @Test
    void anAdjustmentTakesCreditsOffTheBalanceAndTheLineExplainsWhy() throws Exception {
        Account user = userWithTopup();
        String admin = adminToken();
        String key = key();

        adjust(admin, user.id(), key, "-500.5", "Stripe refund re_123 of the small pack").andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(true)).andExpect(jsonPath("$.balance").value(1499.5));

        assertThat(balance(user.id())).isEqualByComparingTo("1499.5");
        Map<String, Object> line = jdbc.queryForMap("select * from credit_ledger where user_id = ? and reason = "
                + "'REFUND_ADJUSTMENT'", user.id());
        assertThat((BigDecimal) line.get("delta")).isEqualByComparingTo("-500.5");
        assertThat((BigDecimal) line.get("balance_after")).isEqualByComparingTo("1499.5");
        // The never-expiring part can never exceed the balance.
        assertThat((BigDecimal) line.get("topup_after")).isEqualByComparingTo("1499.5");
        assertThat(line.get("idempotency_key")).isEqualTo("refund:" + key);
        assertThat(line.get("note")).isEqualTo("Stripe refund re_123 of the small pack");

        // The user sees the line in their own ledger and nobody else does.
        mvc.perform(get("/billing/ledger").header("Authorization", user.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reason").value("REFUND_ADJUSTMENT"))
                .andExpect(jsonPath("$.items[0].delta").value(-500.5))
                .andExpect(jsonPath("$.items[0].balanceAfter").value(1499.5));
        Account other = newAccount();
        mvc.perform(get("/billing/ledger").header("Authorization", other.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void aRepeatWithTheSameKeyIsANoOpAndAReusedKeyForSomethingElseIsRefused() throws Exception {
        Account user = userWithTopup();
        Account other = userWithTopup();
        String admin = adminToken();
        String key = key();

        adjust(admin, user.id(), key, "-300", "refund").andExpect(status().isCreated());
        adjust(admin, user.id(), key, "-300", "refund again, a retry").andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false)).andExpect(jsonPath("$.balance").value(1700.0));
        assertThat(lines(user.id(), "REFUND_ADJUSTMENT")).isEqualTo(1);
        assertThat(balance(user.id())).isEqualByComparingTo("1700");

        // The key is taken: not for another amount, not for another user.
        adjust(admin, user.id(), key, "-400", "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        adjust(admin, other.id(), key, "-300", "x").andExpect(status().isConflict());
        assertThat(lines(user.id(), "REFUND_ADJUSTMENT")).isEqualTo(1);
        assertThat(lines(other.id(), "REFUND_ADJUSTMENT")).isZero();
        assertThat(balance(other.id())).isEqualByComparingTo("2000");
    }

    @Test
    void aKeyCannotCollideWithAGrantOrTopUpKey() throws Exception {
        Account user = newAccount();
        String admin = adminToken();
        // A grant key looks like free:<user>:<period>; the admin's key is namespaced, so using it writes a refund line.
        adjust(admin, user.id(), "free:" + user.id() + ":" + CreditGrants.period(Instant.now()), "-1", "x")
                .andExpect(status().isCreated());
        assertThat(lines(user.id(), "REFUND_ADJUSTMENT")).isEqualTo(1);
        assertThat(grants.grantFreeIfDue(user.id(), Instant.now())).isTrue();
    }

    @Test
    void theBalanceMayGoNegativeAndLaterGrantsPayItOff() throws Exception {
        Account user = newAccount();
        String admin = adminToken();
        grants.grantFreeIfDue(user.id(), Instant.now());
        assertThat(balance(user.id())).isEqualByComparingTo("300");

        adjust(admin, user.id(), key(), "-1000", "refund of credits already spent").andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(-700.0));
        assertThat(balance(user.id())).isEqualByComparingTo("-700");

        // The next month's free grant is added to it.
        grants.grantFreeIfDue(user.id(), Instant.now().plus(java.time.Duration.ofDays(40)));
        assertThat(balance(user.id())).isEqualByComparingTo("-400");
    }

    @Test
    void invalidRequestsAreRejectedAndNothingIsWritten() throws Exception {
        Account user = newAccount();
        String admin = adminToken();

        adjust(admin, user.id(), key(), "500", "positive").andExpect(status().isBadRequest());
        adjust(admin, user.id(), key(), "0", "zero").andExpect(status().isBadRequest());
        adjust(admin, user.id(), key(), "-1.1234567", "seven decimals").andExpect(status().isBadRequest());
        adjust(admin, user.id(), key(), "-5", "").andExpect(status().isBadRequest());
        adjust(admin, user.id(), key(), "-5", null).andExpect(status().isBadRequest());
        adjust(admin, user.id(), "", "-5", "no key").andExpect(status().isBadRequest());
        adjust(admin, user.id(), "k".repeat(101), "-5", "long key").andExpect(status().isBadRequest());
        adjust(admin, user.id(), key(), "-5", "r".repeat(501)).andExpect(status().isBadRequest());
        adjust(admin, UUID.randomUUID(), key(), "-5", "no such user").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user_not_found"));

        assertThat(lines(user.id(), "REFUND_ADJUSTMENT")).isZero();
        // The service itself refuses a non-negative delta too, whoever calls it.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> adjustments.adjust(user.id(), BigDecimal.ONE, key(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentRequestsWithOneKeyWriteOneLine() throws Exception {
        Account user = userWithTopup();
        String key = key();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<CreditAdjustments.Result>> calls = java.util.Collections.nCopies(4,
                    () -> adjustments.adjust(user.id(), new BigDecimal("-100"), key, "race").result());
            int applied = 0;
            int replayed = 0;
            for (Future<CreditAdjustments.Result> result : pool.invokeAll(calls)) {
                switch (result.get()) {
                    case APPLIED -> applied++;
                    case REPLAYED -> replayed++;
                    default -> throw new AssertionError(result.get());
                }
            }
            assertThat(applied).isEqualTo(1);
            assertThat(replayed).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(lines(user.id(), "REFUND_ADJUSTMENT")).isEqualTo(1);
        assertThat(balance(user.id())).isEqualByComparingTo("1900");
    }
}
