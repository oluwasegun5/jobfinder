package com.jobfinder.core.billing.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Account deletion and billing (ADR 0036): the subscription is stopped at the provider best-effort, a failure is
 * recorded for retry and never blocks the deletion, the user's subscription and ledger rows go, and the webhook log
 * (provider ids only) keeps nothing about the person.
 */
class BillingDeletionTests extends PaymentTestSupport {

    @Autowired
    private LedgerService ledger;

    private Account subscriber(String ref) throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        stripe(stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), ref,
                "cus_del", account.id(), "pro", daysFromNow(30))).andExpect(status().isOk());
        ledger.record(usage(newKey(), account.id(), "parse_resume", "0.010"));
        return account;
    }

    private void deleteAccount(Account account) throws Exception {
        mvc.perform(delete("/me").header("Authorization", account.bearer())).andExpect(status().isNoContent());
    }

    @Test
    void deletingTheAccountStopsTheRemoteSubscriptionAndErasesTheUsersBillingRows() throws Exception {
        String ref = "sub_del_" + UUID.randomUUID().toString().substring(0, 8);
        providers().stubFor(WireMock.delete(urlPathEqualTo("/v1/subscriptions/" + ref)).willReturn(aResponse().withStatus(200)
                .withBody("{}")));
        Account account = subscriber(ref);
        assertThat(count("select count(*) from credit_ledger where user_id = ?", account.id())).isPositive();
        int events = webhookEvents("STRIPE");

        deleteAccount(account);

        providers().verify(deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/" + ref)));
        assertThat(count("select count(*) from subscriptions where user_id = ?", account.id())).isZero();
        assertThat(count("select count(*) from credit_ledger where user_id = ?", account.id())).isZero();
        assertThat(count("select count(*) from remote_cancellations where provider_ref = ?", ref)).isZero();
        // The AI cost history stays, without the person.
        assertThat(count("select count(*) from ai_calls where user_id = ?", account.id())).isZero();
        assertThat(webhookEvents("STRIPE")).isEqualTo(events);
    }

    @Test
    void aFailedRemoteCancellationDoesNotBlockTheDeletionAndIsRetriedLater() throws Exception {
        String ref = "sub_delfail_" + UUID.randomUUID().toString().substring(0, 8);
        providers().stubFor(WireMock.delete(urlPathEqualTo("/v1/subscriptions/" + ref)).willReturn(aResponse().withStatus(500)));
        Account account = subscriber(ref);

        deleteAccount(account);

        assertThat(count("select count(*) from users where id = ?", account.id())).isZero();
        assertThat(count("select count(*) from subscriptions where user_id = ?", account.id())).isZero();
        assertThat(count("select count(*) from remote_cancellations where provider = 'STRIPE' and provider_ref = ?",
                ref)).isEqualTo(1);
        // Only provider ids are kept: the table has no user column.
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = "
                + "'remote_cancellations'", String.class)).doesNotContain("user_id");

        // The provider recovers: the retry clears it.
        providers().stubFor(WireMock.delete(urlPathEqualTo("/v1/subscriptions/" + ref)).atPriority(1)
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        remoteCancellations.retryDue(Instant.now().plusSeconds(7 * 86_400L), 100);

        assertThat(count("select count(*) from remote_cancellations where provider_ref = ?", ref)).isZero();
    }

    @Test
    void deletingAFreeUserWithNoSubscriptionCallsNoProvider() throws Exception {
        Account account = newAccount();
        gate.requireAllowance(account.id(), "parse_resume");
        int before = providers().findAll(com.github.tomakehurst.wiremock.client.WireMock
                .deleteRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching(".*"))).size();

        deleteAccount(account);

        assertThat(count("select count(*) from credit_ledger where user_id = ?", account.id())).isZero();
        assertThat(providers().findAll(com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching(".*"))).size()).isEqualTo(before);
    }
}
