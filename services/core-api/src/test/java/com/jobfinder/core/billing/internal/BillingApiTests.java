package com.jobfinder.core.billing.internal;

import com.jobfinder.core.CoversEndpoints;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;

/**
 * The billing API (ADR 0036): the catalogue, the caller's own plan and balance, hosted checkout for both providers
 * (against WireMock; no real provider is ever called), cancellation, the ledger with its cursor, rate limiting, and
 * that one user can never see or touch another's subscription or ledger.
 */
class BillingApiTests extends PaymentTestSupport {

    @Autowired
    private LedgerService ledger;

    private ResultActions getAs(Account account, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", account.bearer()));
    }

    private ResultActions checkout(Account account, String json) throws Exception {
        return mvc.perform(post("/billing/checkout").header("Authorization", account.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions cancel(Account account) throws Exception {
        return mvc.perform(post("/billing/subscription/cancel").header("Authorization", account.bearer()));
    }

    private void stubStripeCheckout() {
        providers().stubFor(WireMock.post(urlPathEqualTo("/v1/checkout/sessions")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"cs_test_1\",\"url\":\"https://checkout.stripe.test/c/pay/cs_test_1\"}")));
    }

    private void stubPaystackInitialize() {
        providers().stubFor(WireMock.post(urlPathEqualTo("/transaction/initialize")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"status":true,"message":"ok","data":{"authorization_url":"https://checkout.paystack.test/abc",
                         "access_code":"ac_1","reference":"r_1"}}""")));
    }

    private static String decoded(LoggedRequest request) {
        return URLDecoder.decode(request.getBodyAsString(), StandardCharsets.UTF_8);
    }

    private Account activeSubscriber(String provider, String ref) throws Exception {
        Account account = newAccount();
        pending(account.id(), provider);
        stripe(stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), ref,
                "cus_" + ref, account.id(), "pro", daysFromNow(30)))
                .andExpect(status().isOk());
        return account;
    }

    @Test
    void everyBillingEndpointRequiresSignIn() throws Exception {
        mvc.perform(get("/billing/plans")).andExpect(status().isUnauthorized());
        mvc.perform(get("/billing/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/billing/ledger")).andExpect(status().isUnauthorized());
        mvc.perform(post("/billing/checkout").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/billing/subscription/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void theCatalogueListsPlansAndPacksWithTheirPricesFromConfiguration() throws Exception {
        Account account = newAccount();

        getAs(account, "/billing/plans").andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[?(@.code=='free')].monthlyCredits").value(300.0))
                .andExpect(jsonPath("$.plans[?(@.code=='pro')].monthlyCredits").value(6000.0))
                .andExpect(jsonPath("$.plans[?(@.code=='pro')].prices[?(@.provider=='STRIPE')].currency")
                        .value("USD"))
                .andExpect(jsonPath("$.plans[?(@.code=='pro')].prices[?(@.provider=='PAYSTACK')].currency")
                        .value("NGN"))
                .andExpect(jsonPath("$.packs[?(@.id=='pack_small')].credits").value(2000))
                .andExpect(jsonPath("$.rolloverCapCredits").value(0));
    }

    @Test
    void aNewUsersAccountShowsTheFreePlanWithTheirFirstGrant() throws Exception {
        Account account = newAccount();

        getAs(account, "/billing/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.code").value("free"))
                .andExpect(jsonPath("$.status").value("FREE"))
                .andExpect(jsonPath("$.subscription").doesNotExist())
                .andExpect(jsonPath("$.balance").value(300))
                .andExpect(jsonPath("$.grantedThisPeriod").value(300))
                .andExpect(jsonPath("$.usedThisPeriod").value(0))
                .andExpect(jsonPath("$.allowance.dailyCap").value(500))
                .andExpect(jsonPath("$.allowance.used").value(0))
                .andExpect(jsonPath("$.periodEnd").isNotEmpty());

        ledger.record(usage(newKey(), account.id(), "parse_resume", "0.050"));
        getAs(account, "/billing/me").andExpect(jsonPath("$.balance").value(250))
                .andExpect(jsonPath("$.usedThisPeriod").value(50))
                .andExpect(jsonPath("$.allowance.used").value(50))
                .andExpect(jsonPath("$.allowance.remaining").value(450));
    }

    @Test
    void aSubscriberSeesTheirPaidPlan() throws Exception {
        Account account = activeSubscriber("STRIPE", "sub_me_" + UUID.randomUUID().toString().substring(0, 8));

        getAs(account, "/billing/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.code").value("pro"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.provider").value("STRIPE"))
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(false))
                .andExpect(jsonPath("$.subscription.currentPeriodEnd").isNotEmpty())
                .andExpect(jsonPath("$.balance").value(6000))
                .andExpect(jsonPath("$.grantedThisPeriod").value(6000));
    }

    @Test
    void aStripePlanCheckoutReturnsTheHostedUrlAndSendsNoCardDataAndNoSecretsInTheBody() throws Exception {
        stubStripeCheckout();
        Account account = newAccount();

        checkout(account, "{\"plan\":\"pro\",\"provider\":\"STRIPE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://checkout.stripe.test/c/pay/cs_test_1"));

        List<LoggedRequest> sent = providers().findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions")));
        LoggedRequest request = sent.get(sent.size() - 1);
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + com.jobfinder.core.PaymentFixtures
                .STRIPE_KEY);
        String body = decoded(request);
        assertThat(body).contains("mode=subscription", "line_items[0][price]=price_PLACEHOLDER_pro_usd",
                "line_items[0][quantity]=1", "metadata[jf_user]=" + account.id(), "metadata[jf_kind]=plan",
                "metadata[jf_item]=pro", "subscription_data[metadata][jf_user]=" + account.id(),
                "success_url=http://localhost:3000/billing/return", "cancel_url=");
        assertThat(body.toLowerCase()).doesNotContain("card", "cvc", "password", "token");
        // A pending subscription row records that checkout started.
        assertThat(subscriptionStatus(account.id())).isEqualTo("PENDING");
    }

    @Test
    void aStripeCreditPackCheckoutUsesAOneOffPayment() throws Exception {
        stubStripeCheckout();
        Account account = newAccount();

        checkout(account, "{\"pack\":\"pack_small\",\"provider\":\"STRIPE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());

        List<LoggedRequest> sent = providers().findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions")));
        String body = decoded(sent.get(sent.size() - 1));
        assertThat(body).contains("mode=payment", "line_items[0][price_data][currency]=usd",
                "line_items[0][price_data][unit_amount]=" + com.jobfinder.core.PaymentFixtures.PACK_SMALL_USD, "metadata[jf_kind]=pack",
                "metadata[jf_item]=pack_small", "payment_intent_data[metadata][jf_user]=" + account.id());
        assertThat(subscriptionRow(account.id())).isNull();
    }

    @Test
    void aPaystackPlanCheckoutInitializesATransactionForTheNairaPlan() throws Exception {
        stubPaystackInitialize();
        Account account = newAccount();

        checkout(account, "{\"plan\":\"pro\",\"provider\":\"PAYSTACK\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://checkout.paystack.test/abc"));

        providers().verify(postRequestedFor(urlPathEqualTo("/transaction/initialize"))
                .withHeader("Authorization", equalTo("Bearer " + com.jobfinder.core.PaymentFixtures.PAYSTACK_KEY))
                .withRequestBody(matchingJsonPath("$.plan", equalTo("PLN_PLACEHOLDER_pro_ngn")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("NGN")))
                .withRequestBody(matchingJsonPath("$.amount", equalTo(Long.toString(com.jobfinder.core.PaymentFixtures.PRO_NGN))))
                .withRequestBody(matchingJsonPath("$.metadata.jf_user", equalTo(account.id().toString())))
                .withRequestBody(matchingJsonPath("$.callback_url", equalTo("http://localhost:3000/billing/return")))
                .withRequestBody(matchingJsonPath("$.email")));
    }

    @Test
    void aPaystackCreditPackCheckoutHasNoPlan() throws Exception {
        stubPaystackInitialize();
        Account account = newAccount();

        checkout(account, "{\"pack\":\"pack_large\",\"provider\":\"PAYSTACK\"}").andExpect(status().isOk());

        providers().verify(postRequestedFor(urlPathEqualTo("/transaction/initialize"))
                .withRequestBody(matchingJsonPath("$.metadata.jf_item", equalTo("pack_large")))
                .withRequestBody(matchingJsonPath("$.metadata.jf_kind", equalTo("pack"))));
    }

    @Test
    void aCheckoutThatMakesNoSenseIsRefused() throws Exception {
        Account account = newAccount();

        // Neither, or both, of plan and pack.
        checkout(account, "{\"provider\":\"STRIPE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_checkout"));
        checkout(account, "{\"plan\":\"pro\",\"pack\":\"pack_small\",\"provider\":\"STRIPE\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_checkout"));
        // A provider that does not exist, or no provider at all.
        checkout(account, "{\"plan\":\"pro\",\"provider\":\"PAYPAL\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("provider_not_offered"));
        checkout(account, "{\"plan\":\"pro\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        // The free plan is not for sale, and unknown plans and packs do not exist.
        checkout(account, "{\"plan\":\"free\",\"provider\":\"STRIPE\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("plan_not_found"));
        checkout(account, "{\"plan\":\"platinum\",\"provider\":\"STRIPE\"}").andExpect(status().isNotFound());
        checkout(account, "{\"pack\":\"pack_huge\",\"provider\":\"STRIPE\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("pack_not_found"));
        assertThat(subscriptionRow(account.id())).isNull();
    }

    @Test
    void aProviderOutageIsA502WithAStableCode() throws Exception {
        providers().stubFor(WireMock.post(urlPathEqualTo("/v1/checkout/sessions")).atPriority(1)
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));
        Account account = newAccount();

        checkout(account, "{\"plan\":\"pro\",\"provider\":\"STRIPE\"}").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("payment_provider_unavailable"));
        providers().resetAll();
    }

    @Test
    void aUserWhoAlreadyHasAPaidPlanCannotStartAnotherCheckoutForIt() throws Exception {
        stubStripeCheckout();
        Account account = activeSubscriber("STRIPE", "sub_dup_" + UUID.randomUUID().toString().substring(0, 8));

        checkout(account, "{\"plan\":\"pro\",\"provider\":\"STRIPE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_subscribed"));
        // A credit pack is still fine.
        checkout(account, "{\"pack\":\"pack_small\",\"provider\":\"STRIPE\"}").andExpect(status().isOk());
    }

    @CoversEndpoints({"POST /billing/checkout"})
    @Test
    void anotherUsersPaidPlanNeitherBlocksNorIsTouchedByMyCheckout() throws Exception {
        stubStripeCheckout();
        Account a = activeSubscriber("STRIPE", "sub_co_" + UUID.randomUUID().toString().substring(0, 8));
        Account b = newAccount();

        // The body names no user; B starts a plan checkout although A holds one, and it is bound to B alone.
        checkout(b, "{\"plan\":\"pro\",\"provider\":\"STRIPE\",\"userId\":\"" + a.id() + "\"}")
                .andExpect(status().isOk());
        assertThat(subscriptionRow(a.id())).containsEntry("status", "ACTIVE");
        getAs(a, "/billing/me").andExpect(jsonPath("$.status").value("ACTIVE"));
        getAs(b, "/billing/me").andExpect(jsonPath("$.subscription.status").value("PENDING"));
        assertThat(subscriptionRow(b.id())).containsEntry("status", "PENDING");
    }

    @Test
    void checkoutIsRateLimitedPerUser() throws Exception {
        stubStripeCheckout();
        Account account = newAccount();
        for (int i = 0; i < 10; i++) {
            checkout(account, "{\"pack\":\"pack_small\",\"provider\":\"STRIPE\"}").andExpect(status().isOk());
        }

        checkout(account, "{\"pack\":\"pack_small\",\"provider\":\"STRIPE\"}")
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
        // Someone else is not affected.
        checkout(newAccount(), "{\"pack\":\"pack_small\",\"provider\":\"STRIPE\"}").andExpect(status().isOk());
    }

    @Test
    void cancellingStopsRenewalAtTheProviderKeepsThePlanAndIsIdempotent() throws Exception {
        String ref = "sub_cancel_" + UUID.randomUUID().toString().substring(0, 8);
        providers().stubFor(WireMock.post(urlPathEqualTo("/v1/subscriptions/" + ref)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"" + ref + "\"}")));
        Account account = activeSubscriber("STRIPE", ref);

        cancel(account).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.code").value("pro"))
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(true));
        cancel(account).andExpect(status().isOk()).andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(true));

        List<LoggedRequest> calls = providers().findAll(postRequestedFor(urlPathEqualTo("/v1/subscriptions/" + ref)));
        assertThat(calls).hasSize(1);
        assertThat(decoded(calls.get(0))).isEqualTo("cancel_at_period_end=true");
        assertThat(subscriptionRow(account.id())).containsEntry("status", "ACTIVE")
                .containsEntry("cancel_at_period_end", true);
    }

    @Test
    void cancellingWithNothingToCancelIsAHarmlessOk() throws Exception {
        Account account = newAccount();

        cancel(account).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FREE"));
    }

    @Test
    void aFailedCancelAtTheProviderChangesNothingHere() throws Exception {
        String ref = "sub_cancelfail_" + UUID.randomUUID().toString().substring(0, 8);
        providers().stubFor(WireMock.post(urlPathEqualTo("/v1/subscriptions/" + ref)).willReturn(aResponse().withStatus(500)));
        Account account = activeSubscriber("STRIPE", ref);

        cancel(account).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("payment_provider_unavailable"));

        assertThat(subscriptionRow(account.id())).containsEntry("cancel_at_period_end", false);
    }

    @Test
    void theLedgerIsPagedWithACursorNewestFirst() throws Exception {
        Account account = newAccount();
        gate.requireAllowance(account.id(), "parse_resume");
        for (int i = 0; i < 4; i++) {
            ledger.record(usage(newKey(), account.id(), "parse_resume", "0.00" + (i + 1)));
        }

        String first = getAs(account, "/billing/ledger?limit=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        String second = getAs(account, "/billing/ledger?limit=2&cursor=" + cursor).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andReturn().getResponse().getContentAsString();
        String third = getAs(account, "/billing/ledger?limit=2&cursor=" + JsonPath.read(second, "$.nextCursor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist()).andReturn().getResponse().getContentAsString();

        List<Integer> ids = new ArrayList<>();
        for (String page : List.of(first, second, third)) {
            ids.addAll(JsonPath.read(page, "$.items[*].id"));
        }
        assertThat(ids).hasSize(5).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // The oldest line is the free grant; the newest is the last usage, with the feature it was for.
        assertThat((String) JsonPath.read(third, "$.items[0].reason")).isEqualTo("PLAN_GRANT");
        assertThat((String) JsonPath.read(first, "$.items[0].reason")).isEqualTo("AI_USAGE");
        assertThat((String) JsonPath.read(first, "$.items[0].feature")).isEqualTo("parse_resume");
        getAs(account, "/billing/ledger?cursor=not-a-cursor").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    @CoversEndpoints({"GET /billing/ledger", "GET /billing/me", "POST /billing/subscription/cancel"})
    @Test
    void oneUserNeverSeesOrTouchesAnotherUsersLedgerOrSubscription() throws Exception {
        String ref = "sub_owner_" + UUID.randomUUID().toString().substring(0, 8);
        providers().stubFor(WireMock.post(urlPathEqualTo("/v1/subscriptions/" + ref)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        Account a = activeSubscriber("STRIPE", ref);
        ledger.record(usage(newKey(), a.id(), "parse_resume", "0.010"));
        Account b = newAccount();
        ledger.record(usage(newKey(), b.id(), "parse_resume", "0.020"));

        // B's ledger holds only B's lines, even when asked with A's newest line id as the cursor.
        long aNewest = jdbc.queryForObject("select max(id) from credit_ledger", Long.class);
        Set<Integer> aIds = new HashSet<>(jdbc.queryForList("select id from credit_ledger where user_id = ?",
                Integer.class, a.id()));
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(aNewest + 1).getBytes(StandardCharsets.UTF_8));
        for (String path : List.of("/billing/ledger?limit=100", "/billing/ledger?limit=100&cursor=" + cursor)) {
            String body = getAs(b, path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<Integer> ids = JsonPath.read(body, "$.items[*].id");
            assertThat(ids).isNotEmpty().doesNotContainAnyElementsOf(aIds);
        }

        // B's figures are B's own (the 300 free credits less 20 used): none of A's 6,000 granted credits or 10 used credits
        // leaks into B's balance, grant and usage totals, and an extra userId parameter is ignored. A's are unchanged.
        getAs(b, "/billing/me?userId=" + a.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(280)).andExpect(jsonPath("$.grantedThisPeriod").value(300))
                .andExpect(jsonPath("$.usedThisPeriod").value(20));
        getAs(a, "/billing/me").andExpect(jsonPath("$.balance").value(5990))
                .andExpect(jsonPath("$.grantedThisPeriod").value(6000)).andExpect(jsonPath("$.usedThisPeriod").value(10));

        // B has no subscription of A's to see, and cancelling does nothing to A's.
        getAs(b, "/billing/me").andExpect(jsonPath("$.plan.code").value("free"))
                .andExpect(jsonPath("$.subscription").doesNotExist());
        cancel(b).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FREE"));
        assertThat(subscriptionRow(a.id())).containsEntry("cancel_at_period_end", false).containsEntry("status",
                "ACTIVE");
        assertThat(providers().findAll(postRequestedFor(urlPathEqualTo("/v1/subscriptions/" + ref)))).isEmpty();
        // And A still sees theirs.
        getAs(a, "/billing/me").andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.cancelAtPeriodEnd").value(false));
        // Neither endpoint takes a user id, so there is no parameter to try: an extra one is ignored.
        String withUserParam = getAs(b, "/billing/ledger?userId=" + a.id()).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat((List<Integer>) JsonPath.read(withUserParam, "$.items[*].id")).doesNotContainAnyElementsOf(aIds);
    }
}
