package com.jobfinder.core.billing.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * The Starter tier once the owner has configured it (docs/adr/0036, G3 addendum): it is listed between Free and Pro,
 * checkout starts with both providers (against WireMock), and a matching first payment grants its 2,500 credits.
 */
@TestPropertySource(properties = { "BILLING_PRICE_STARTER_USD_MINOR=" + StarterPlanApiTests.STARTER_USD,
        "BILLING_PRICE_STARTER_NGN_MINOR=" + StarterPlanApiTests.STARTER_NGN,
        "STRIPE_PRICE_STARTER_USD=price_1TestStarterUsd", "PAYSTACK_PLAN_STARTER_NGN=PLN_teststarter" })
class StarterPlanApiTests extends PaymentTestSupport {

    static final long STARTER_USD = 500;
    static final long STARTER_NGN = 450_000;

    private ResultActions checkout(Account account, String json) throws Exception {
        return mvc.perform(post("/billing/checkout").header("Authorization", account.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void aConfiguredStarterIsListedBetweenFreeAndProWithBothPrices() throws Exception {
        mvc.perform(get("/billing/plans")).andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].code").value("free"))
                .andExpect(jsonPath("$.plans[1].code").value("starter"))
                .andExpect(jsonPath("$.plans[1].name").value("Starter"))
                .andExpect(jsonPath("$.plans[1].monthlyCredits").value(2500.0))
                .andExpect(jsonPath("$.plans[1].prices[?(@.provider=='STRIPE' && @.currency=='USD')].amountMinor")
                        .value((int) STARTER_USD))
                .andExpect(jsonPath("$.plans[1].prices[?(@.provider=='PAYSTACK' && @.currency=='NGN')].amountMinor")
                        .value((int) STARTER_NGN))
                .andExpect(jsonPath("$.plans[2].code").value("pro"));
    }

    @Test
    void aStarterCheckoutStartsWithStripeAndAMatchingPaymentGrantsItsCredits() throws Exception {
        providers().stubFor(WireMock.post(urlPathEqualTo("/v1/checkout/sessions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"cs_test_s\",\"url\":\"https://checkout.stripe.test/c/pay/cs_test_s\"}")));
        Account account = newAccount();

        checkout(account, "{\"plan\":\"starter\",\"provider\":\"STRIPE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://checkout.stripe.test/c/pay/cs_test_s"));
        List<LoggedRequest> sent = providers().findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions")));
        String body = URLDecoder.decode(sent.get(sent.size() - 1).getBodyAsString(), StandardCharsets.UTF_8);
        assertThat(body).contains("mode=subscription", "line_items[0][price]=price_1TestStarterUsd",
                "metadata[jf_item]=starter");

        String ref = "sub_starter_" + account.id().toString().substring(0, 8);
        stripe(stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(ChronoUnit.SECONDS), ref, "cus_" + ref,
                account.id(), "starter", daysFromNow(30), STARTER_USD, "usd")).andExpect(status().isOk());

        mvc.perform(get("/billing/me").header("Authorization", account.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.code").value("starter"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        BigDecimal granted = jdbc.queryForObject("select delta from credit_ledger where user_id = ? and reason = "
                + "'PLAN_GRANT' order by id desc limit 1", BigDecimal.class, account.id());
        assertThat(granted).isEqualByComparingTo("2500");
    }

    @Test
    void aStarterCheckoutStartsWithPaystackInNaira() throws Exception {
        providers().stubFor(WireMock.post(urlPathEqualTo("/transaction/initialize")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"status":true,"message":"ok","data":{"authorization_url":"https://checkout.paystack.test/s",
                         "access_code":"ac_s","reference":"r_s"}}""")));
        Account account = newAccount();

        checkout(account, "{\"plan\":\"starter\",\"provider\":\"PAYSTACK\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://checkout.paystack.test/s"));

        providers().verify(postRequestedFor(urlPathEqualTo("/transaction/initialize"))
                .withRequestBody(matchingJsonPath("$.plan", equalTo("PLN_teststarter")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("NGN")))
                .withRequestBody(matchingJsonPath("$.amount", equalTo(Long.toString(STARTER_NGN))))
                .withRequestBody(matchingJsonPath("$.metadata.jf_item", equalTo("starter"))));
    }
}
