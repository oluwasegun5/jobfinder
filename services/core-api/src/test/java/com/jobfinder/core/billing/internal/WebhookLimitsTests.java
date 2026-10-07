package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jobfinder.core.PaymentFixtures;

/**
 * The webhook endpoints refuse a body over 1 MB with 413 before any signature work, however the size is revealed
 * (Content-Length, or a stream that never ends), and a normal signed delivery still passes.
 */
class WebhookLimitsTests extends PaymentTestSupport {

    private static byte[] tooBig() {
        byte[] body = new byte[WebhookController.MAX_BODY_BYTES + 1];
        Arrays.fill(body, (byte) 'a');
        return body;
    }

    private ResultActions raw(String path, byte[] body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void anOversizeBodyIsRefusedWith413BeforeTheSignatureIsLookedAt() throws Exception {
        int stripeEvents = webhookEvents("STRIPE");
        int paystackEvents = webhookEvents("PAYSTACK");

        // No signature at all: a 413 (not the 400 a bad signature gets) proves the size is checked first.
        raw("/webhooks/stripe", tooBig()).andExpect(status().is(413)).andExpect(content().string(""));
        raw("/webhooks/paystack", tooBig()).andExpect(status().is(413)).andExpect(content().string(""));

        assertThat(webhookEvents("STRIPE")).isEqualTo(stripeEvents);
        assertThat(webhookEvents("PAYSTACK")).isEqualTo(paystackEvents);
    }

    @Test
    void aBodyOfExactlyTheLimitIsNotTooLargeAndFailsOnItsSignatureInstead() throws Exception {
        byte[] exactly = Arrays.copyOf(tooBig(), WebhookController.MAX_BODY_BYTES);

        raw("/webhooks/stripe", exactly).andExpect(status().isBadRequest());
        raw("/webhooks/paystack", exactly).andExpect(status().isBadRequest());
    }

    @Test
    void aStreamedBodyWithoutAContentLengthIsRefusedWith413ThroughTheWholeChain() throws Exception {
        for (String path : new String[] { "/webhooks/stripe", "/webhooks/paystack" }) {
            WebhookBodyLimitTests.EndlessRequest[] endless = new WebhookBodyLimitTests.EndlessRequest[1];
            // The request that reaches the filter chain claims no length and streams without end.
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).with(original -> {
                endless[0] = new WebhookBodyLimitTests.EndlessRequest(original.getServletContext(), path, -1);
                endless[0].setContentType("application/json");
                endless[0].setAsyncSupported(true);
                return endless[0];
            })).andExpect(status().is(413));
            // The endless stream was cut off at the limit, not drained.
            assertThat(endless[0].served.get()).isEqualTo(WebhookController.MAX_BODY_BYTES + 1L);
        }
    }

    @Test
    void aNormalSignedDeliveryStillPasses() throws Exception {
        Account account = newAccount();
        pending(account.id(), "STRIPE");
        stripe(stripeInvoicePaid(newEventId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                "sub_limits1", "cus_limits1", account.id(), "pro", daysFromNow(30))).andExpect(status().isOk());
        assertThat(subscriptionStatus(account.id())).isEqualTo("ACTIVE");

        Account other = newAccount();
        String body = paystackChargePack(other.id(), "pack_small", "ref_limits_" + other.id(), Instant.now());
        paystack(body).andExpect(status().isOk());
        assertThat(balance(other.id())).isEqualByComparingTo("2000");

        // A signed 900 KB body (large but legitimate-looking) reaches signature verification, and passes it when the
        // signature is right; it is an unknown event type, so it is acknowledged.
        String padded = stripeEvent(newEventId(), "some.other.event", Instant.now(),
                "{\"pad\":\"" + "p".repeat(900 * 1024) + "\"}");
        stripe(padded, stripeSignature(padded.getBytes(StandardCharsets.UTF_8), Instant.now().getEpochSecond(),
                PaymentFixtures.STRIPE_WEBHOOK_SECRET)).andExpect(status().isOk());
    }
}
