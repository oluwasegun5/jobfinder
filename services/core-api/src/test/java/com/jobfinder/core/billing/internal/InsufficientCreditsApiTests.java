package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.jobfinder.core.profile.ResumeParsingTestSupport;

/**
 * What a user at zero credits sees from a real AI endpoint (resume parsing): the RFC 7807 problem with the distinct
 * type and code the web client turns into an upgrade prompt, and, for the background worker, the stored reason.
 */
class InsufficientCreditsApiTests extends ResumeParsingTestSupport {

    @Autowired
    private DailyCapService gate;

    @Autowired
    private CreditLedgerStore store;

    @Autowired
    private CreditGrants grants;

    private static String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    private void spendEverything(UUID user) {
        store.append(user, store.balance(user).negate(), LedgerReason.REFUND_ADJUSTMENT, null,
                "test:zero:" + UUID.randomUUID(), Instant.now());
    }

    @Test
    void anEndpointAnswers402WithTheInsufficientCreditsProblemAndWorksAgainAfterATopUp() throws Exception {
        Session session = newSession();
        UUID user = userIdOf(session.accessToken());
        stubParseOk(user);
        gate.status(user);
        spendEverything(user);
        UUID resume = uploadPdf(session);
        awaitStatus(resume, "FAILED");

        mvc.perform(post("/resumes/" + resume + "/reparse").header("Authorization", bearer(session)))
                .andExpect(status().isPaymentRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:jobfinder:problem:insufficient-credits"))
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.code").value("insufficient_credits"))
                .andExpect(jsonPath("$.balance").value("0"));
        assertThat(parseRequests(user)).isZero();

        grants.topup(user, new BigDecimal("500"), Provider.STRIPE, "pi_api_" + UUID.randomUUID(), Instant.now());

        mvc.perform(post("/resumes/" + resume + "/reparse").header("Authorization", bearer(session)))
                .andExpect(status().isAccepted());
    }

    @Test
    void theBackgroundParseOfAUserWithNoCreditsIsFailedWithAClearReasonAndMakesNoCall() throws Exception {
        Session session = newSession();
        UUID user = userIdOf(session.accessToken());
        stubParseOk(user);
        gate.status(user);
        spendEverything(user);

        UUID resume = uploadPdf(session);
        awaitStatus(resume, "FAILED");

        assertThat(parseError(resume)).isEqualTo("insufficient_credits");
        assertThat(parseRequests(user)).isZero();
        mvc.perform(get("/resumes").header("Authorization", bearer(session)))
                .andExpect(jsonPath("$[0].parseError").value("insufficient_credits"));
    }
}
