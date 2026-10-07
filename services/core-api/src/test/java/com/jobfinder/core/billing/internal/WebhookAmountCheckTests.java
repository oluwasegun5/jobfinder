package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.jobfinder.core.PaymentFixtures;

/**
 * Credits are granted only for what the catalog says the item costs: a payment of another amount, in another currency,
 * or of nothing (a 100% coupon) is still acknowledged and recorded, but grants nothing and says so at WARN.
 */
@ExtendWith(OutputCaptureExtension.class)
class WebhookAmountCheckTests extends PaymentTestSupport {

    private static String sub() {
        return "sub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private static String cus() {
        return "cus_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static String intent() {
        return "pi_" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static String ref() {
        return "ref_" + UUID.randomUUID().toString().substring(0, 12);
    }

    // ---- Stripe plan payments ------------------------------------------------------------------------------------

    @Test
    void aStripePaymentOfTheCatalogPriceCreditsThePlan() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");

        stripe(stripeInvoicePaid(newEventId(), now(), sub(), cus(), account.id(), "pro", daysFromNow(30),
                PaymentFixtures.PRO_USD, "usd")).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void taxOnTopOfTheCatalogPriceStillCredits() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        String body = stripeInvoicePaid(newEventId(), now(), sub(), cus(), account.id(), "pro", daysFromNow(30),
                PaymentFixtures.PRO_USD, "usd").replace("\"amount_paid\":" + PaymentFixtures.PRO_USD,
                        "\"amount_paid\":" + (PaymentFixtures.PRO_USD + 112));

        stripe(body).andExpect(status().isOk());

        assertThat(balance(account.id())).isEqualByComparingTo("6000");
    }

    @Test
    void aStripePaymentOfAnotherAmountGrantsNothingButIsAcknowledgedRecordedAndLogged(CapturedOutput output)
            throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        String eventId = newEventId();

        stripe(stripeInvoicePaid(eventId, now(), sub(), cus(), account.id(), "pro", daysFromNow(30),
                PaymentFixtures.PRO_USD - 1, "usd")).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isZero();
        assertThat(balance(account.id())).isEqualByComparingTo("0");
        assertThat(count("select count(*) from webhook_events where provider = 'STRIPE' and event_id = ?", eventId))
                .isEqualTo(1);
        assertThat(output.getOut()).contains("Payment does not match the catalog price").contains("id=" + eventId)
                .contains("expected=" + PaymentFixtures.PRO_USD + " usd").contains("actual=" + (PaymentFixtures.PRO_USD - 1));
    }

    @Test
    void aStripePaymentInAnotherCurrencyGrantsNothing() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");

        stripe(stripeInvoicePaid(newEventId(), now(), sub(), cus(), account.id(), "pro", daysFromNow(30),
                PaymentFixtures.PRO_USD, "eur")).andExpect(status().isOk());
        // NGN is a catalog currency, but of Paystack, not Stripe.
        Account other = newAccount();
        pending(other.id(), "STRIPE");
        stripe(stripeInvoicePaid(newEventId(), now(), sub(), cus(), other.id(), "pro", daysFromNow(30),
                PaymentFixtures.PRO_NGN, "ngn")).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isZero();
        assertThat(lines(other.id(), "PLAN_GRANT")).isZero();
    }

    @Test
    void aZeroAmountInvoiceFromAFullCouponGrantsNothing() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");

        stripe(stripeInvoicePaid(newEventId(), now(), sub(), cus(), account.id(), "pro", daysFromNow(30), 0, "usd"))
                .andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isZero();
        assertThat(balance(account.id())).isEqualByComparingTo("0");
    }

    @Test
    void anInvoiceThatStatesNoAmountGrantsNothing() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        String body = stripeInvoicePaid(newEventId(), now(), sub(), cus(), account.id(), "pro", daysFromNow(30))
                .replaceAll("\"amount_paid\":\\d+,\"total_excluding_tax\":\\d+,", "");

        stripe(body).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isZero();
    }

    @Test
    void aLaterPaymentAtTheRightPriceCreditsItsPeriodEvenAfterAMismatchedOne() throws Exception {
        Account account = newAccount();
        String sub = sub();
        String cus = cus();
        pending(account.id(), "STRIPE");
        stripe(stripeInvoicePaid(newEventId(), now().minusSeconds(60), sub, cus, account.id(), "pro", daysFromNow(1),
                0, "usd")).andExpect(status().isOk());
        assertThat(lines(account.id(), "PLAN_GRANT")).isZero();

        stripe(stripeInvoicePaid(newEventId(), now(), sub, cus, null, null, daysFromNow(31))).andExpect(status().isOk());

        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
    }

    // ---- Stripe top-ups -----------------------------------------------------------------------------------------

    @Test
    void aStripePackPaidAtItsPriceCreditsAndOtherAmountsOrCurrenciesDoNot() throws Exception {
        Account account = newAccount();

        stripe(stripePackCheckout(newEventId(), now(), intent(), account.id(), "pack_small")).andExpect(status().isOk());
        assertThat(balance(account.id())).isEqualByComparingTo("2000");

        // The cheap pack's amount for the large pack, a wrong currency, and nothing paid: no further credit.
        stripe(stripePackCheckout(newEventId(), now(), intent(), account.id(), "pack_large",
                PaymentFixtures.PACK_SMALL_USD, "usd")).andExpect(status().isOk());
        stripe(stripePackCheckout(newEventId(), now(), intent(), account.id(), "pack_large",
                PaymentFixtures.PACK_LARGE_USD, "eur")).andExpect(status().isOk());
        stripe(stripePackCheckout(newEventId(), now(), intent(), account.id(), "pack_large", 0, "usd"))
                .andExpect(status().isOk());

        assertThat(lines(account.id(), "TOPUP")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("2000");

        stripe(stripePackCheckout(newEventId(), now(), intent(), account.id(), "pack_large")).andExpect(status().isOk());
        assertThat(balance(account.id())).isEqualByComparingTo("12000");
    }

    // ---- Paystack -----------------------------------------------------------------------------------------------

    @Test
    void aPaystackPlanChargeCreditsAtTheCatalogPriceAndNotOtherwise() throws Exception {
        Account good = newAccount();
        pending(good.id(), "PAYSTACK");
        paystack(paystackChargePlan(good.id(), "pro", ref(), cus(), now())).andExpect(status().isOk());
        assertThat(balance(good.id())).isEqualByComparingTo("6000");

        Account wrongAmount = newAccount();
        pending(wrongAmount.id(), "PAYSTACK");
        paystack(paystackChargePlan(wrongAmount.id(), "pro", ref(), cus(), now(), PaymentFixtures.PRO_NGN - 1, "NGN"))
                .andExpect(status().isOk());
        Account wrongCurrency = newAccount();
        pending(wrongCurrency.id(), "PAYSTACK");
        paystack(paystackChargePlan(wrongCurrency.id(), "pro", ref(), cus(), now(), PaymentFixtures.PRO_NGN, "USD"))
                .andExpect(status().isOk());
        Account free = newAccount();
        pending(free.id(), "PAYSTACK");
        paystack(paystackChargePlan(free.id(), "pro", ref(), cus(), now(), 0, "NGN")).andExpect(status().isOk());

        for (Account account : new Account[] { wrongAmount, wrongCurrency, free }) {
            assertThat(lines(account.id(), "PLAN_GRANT")).isZero();
            assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");
        }
    }

    @Test
    void aPaystackRenewalAtAnotherAmountDoesNotCredit() throws Exception {
        Account account = newAccount();
        String cus = cus();
        pending(account.id(), "PAYSTACK");
        Instant first = now().minusSeconds(3600);
        paystack(paystackChargePlan(account.id(), "pro", ref(), cus, first)).andExpect(status().isOk());
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);

        paystack(paystackChargeRenewal(ref(), cus, now(), PaymentFixtures.PRO_NGN + 5000, "NGN"))
                .andExpect(status().isOk());
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(1);

        paystack(paystackChargeRenewal(ref(), cus, now().plusSeconds(5))).andExpect(status().isOk());
        assertThat(lines(account.id(), "PLAN_GRANT")).isEqualTo(2);
    }

    @Test
    void aPaystackPackCreditsAtItsPriceAndNotOtherwise() throws Exception {
        Account account = newAccount();

        paystack(paystackChargePack(account.id(), "pack_small", ref(), now(), PaymentFixtures.PACK_SMALL_NGN + 1,
                "NGN")).andExpect(status().isOk());
        paystack(paystackChargePack(account.id(), "pack_small", ref(), now(), PaymentFixtures.PACK_SMALL_NGN, "USD"))
                .andExpect(status().isOk());
        paystack(paystackChargePack(account.id(), "pack_small", ref(), now(), 0, "NGN")).andExpect(status().isOk());
        assertThat(lines(account.id(), "TOPUP")).isZero();

        paystack(paystackChargePack(account.id(), "pack_small", ref(), now())).andExpect(status().isOk());
        assertThat(lines(account.id(), "TOPUP")).isEqualTo(1);
        assertThat(balance(account.id())).isEqualByComparingTo("2000");
    }
}
