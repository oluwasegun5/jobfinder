package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Refunds and disputes are not reversed automatically (an admin takes the credits back, see
 * AdminCreditAdjustmentTests), but they are no longer dropped silently: each is acknowledged, recorded once and logged
 * at WARN with the event id, the type and the user when the event names one, and never its payload.
 */
@ExtendWith(OutputCaptureExtension.class)
class WebhookRefundLogTests extends PaymentTestSupport {

    private static final String SECRET_MARKER = "payload-marker-do-not-log-4711";

    @Test
    void aStripeRefundIsLoggedAtWarnWithIdTypeAndUserAndChangesNoCredits(CapturedOutput output) throws Exception {
        Account account = newAccount();
        stripe(stripePackCheckout(newEventId(), Instant.now(), "pi_ref_" + UUID.randomUUID().toString().substring(0, 8),
                account.id(), "pack_small")).andExpect(status().isOk());
        int lines = ledgerLines(account.id());
        String eventId = newEventId();
        String body = stripeEvent(eventId, "charge.refunded", Instant.now(), """
                {"id":"ch_1","object":"charge","refunded":true,"description":"%s",
                 "metadata":{"jf_user":"%s","jf_kind":"pack","jf_item":"pack_small"}}""".formatted(SECRET_MARKER,
                account.id()));

        stripe(body).andExpect(status().isOk());

        assertThat(output.getOut()).contains("WARN").contains("Refund or dispute needs a manual look")
                .contains("provider=stripe").contains("id=" + eventId).contains("type=charge.refunded")
                .contains("user=" + account.id()).doesNotContain(SECRET_MARKER);
        assertThat(ledgerLines(account.id())).isEqualTo(lines);
        assertThat(balance(account.id())).isEqualByComparingTo("2000");
        // Claimed like any handled event: a redelivery is a duplicate and is not warned about twice.
        assertThat(count("select count(*) from webhook_events where provider = 'STRIPE' and event_id = ?", eventId))
                .isEqualTo(1);
        stripe(body).andExpect(status().isOk());
        assertThat(output.getOut().split("id=" + eventId + " type=charge.refunded user=", -1).length - 1).isEqualTo(1);
    }

    @Test
    void everyStripeDisputeEventIsLoggedEvenWhenNoUserIsKnown(CapturedOutput output) throws Exception {
        for (String type : new String[] { "charge.dispute.created", "charge.dispute.funds_withdrawn",
                "charge.dispute.closed" }) {
            String eventId = newEventId();
            stripe(stripeEvent(eventId, type, Instant.now(), "{\"id\":\"dp_1\",\"note\":\"" + SECRET_MARKER + "\"}"))
                    .andExpect(status().isOk());
            assertThat(output.getOut()).contains("id=" + eventId + " type=" + type + " user=null");
        }
        assertThat(output.getOut()).doesNotContain(SECRET_MARKER);
    }

    @Test
    void aPaystackRefundIsLoggedAtWarn(CapturedOutput output) throws Exception {
        for (String type : new String[] { "refund.pending", "refund.processed", "refund.failed" }) {
            String body = "{\"event\":\"" + type + "\",\"data\":{\"transaction_reference\":\"ref_"
                    + UUID.randomUUID().toString().substring(0, 8) + "\",\"amount\":500000,\"note\":\""
                    + SECRET_MARKER + "\",\"created_at\":\"" + Instant.now() + "\"}}";
            String eventId = Hmac.sha256Hex(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            paystack(body).andExpect(status().isOk());

            assertThat(output.getOut()).contains("Refund or dispute needs a manual look")
                    .contains("provider=paystack").contains("id=" + eventId).contains("type=" + type);
            assertThat(count("select count(*) from webhook_events where provider = 'PAYSTACK' and event_id = ?",
                    eventId)).isEqualTo(1);
        }
        assertThat(output.getOut()).doesNotContain(SECRET_MARKER);
    }

    @Test
    void aBadSignatureOnARefundEventStillGetsA400AndNoWarning(CapturedOutput output) throws Exception {
        String eventId = newEventId();
        stripe(stripeEvent(eventId, "charge.refunded", Instant.now(), "{\"id\":\"ch_2\"}"), "t=1,v1=bad")
                .andExpect(status().isBadRequest());
        assertThat(output.getOut()).doesNotContain("id=" + eventId + " type=charge.refunded");
    }
}
