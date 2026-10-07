package com.jobfinder.core.billing.internal;

import com.jobfinder.core.CoversEndpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.jobfinder.core.billing.AiDailyCapReachedException;
import com.jobfinder.core.billing.Allowance;

/**
 * The per-user daily cap, here 10 credits a UTC day ($0.01 at 1,000 micro-dollars per credit): what counts towards
 * it, the typed error with the reset time, the reset at midnight UTC, and the user's own view of the allowance.
 */
@TestPropertySource(properties = { "app.billing.daily-cap-credits=10", "app.billing.micro-usd-per-credit=1000" })
class DailyCapTests extends BillingTestSupport {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private DailyCapService caps;

    private static Instant startOfToday() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Test
    void aUserBelowTheCapIsAllowedAndSeesWhatIsLeft() throws Exception {
        UUID user = newUser();
        ledger.record(usage(newKey(), user, "parse_resume", "0.006"));

        Allowance allowance = caps.allowance(user);

        assertThat(allowance.dailyCap()).isEqualByComparingTo("10");
        assertThat(allowance.used()).isEqualByComparingTo("6");
        assertThat(allowance.remaining()).isEqualByComparingTo("4");
        assertThat(allowance.exhausted()).isFalse();
        assertThatCode(() -> caps.requireAllowance(user, "parse_resume")).doesNotThrowAnyException();
    }

    @Test
    void aUserAtTheCapIsBlockedWithATypedErrorAndTheResetTime() throws Exception {
        UUID user = newUser();
        ledger.record(usage(newKey(), user, "parse_resume", "0.006"));
        // The call that crosses the line is allowed (the check is before a call), and is recorded in full.
        ledger.record(usage(newKey(), user, "embed_resume", "0.007"));

        assertThatThrownBy(() -> caps.requireAllowance(user, "parse_resume"))
                .isInstanceOfSatisfying(AiDailyCapReachedException.class, e -> {
                    assertThat(e.code()).isEqualTo("ai_daily_cap_reached");
                    assertThat(e.status().value()).isEqualTo(429);
                    assertThat(e.resetsAt()).isEqualTo(startOfToday().plusSeconds(86_400));
                    assertThat(e.properties()).containsEntry("resetsAt", e.resetsAt().toString());
                    assertThat(e.headers().getFirst("Retry-After")).isNotNull();
                });
        assertThat(caps.allowance(user).remaining()).isEqualByComparingTo("0");
    }

    @Test
    void theCapIsPerUser() throws Exception {
        UUID spender = newUser();
        UUID other = newUser();
        ledger.record(usage(newKey(), spender, "parse_resume", "0.02"));

        assertThatThrownBy(() -> caps.requireAllowance(spender, "parse_resume"))
                .isInstanceOf(AiDailyCapReachedException.class);
        assertThatCode(() -> caps.requireAllowance(other, "parse_resume")).doesNotThrowAnyException();
    }

    @Test
    void systemCallsAndFreeCallsDoNotCount() throws Exception {
        UUID user = newUser();
        ledger.record(usage(newKey(), null, "embed_job", "5.00"));
        ledger.record(usage(newKey(), user, "embed_resume", "0"));

        assertThat(caps.allowance(user).used()).isEqualByComparingTo("0");
    }

    @Test
    void theAllowanceStartsAfreshAtMidnightUtc() throws Exception {
        UUID user = newUser();
        Instant now = Instant.now();
        ledger.record(usage(newKey(), user, "parse_resume", "0.02"), now);
        Instant tomorrow = startOfToday().plusSeconds(86_400 + 60);

        assertThatThrownBy(() -> caps.requireAllowance(user, "parse_resume", now))
                .isInstanceOf(AiDailyCapReachedException.class);
        // The next UTC day: nothing is counted any more, and the new reset is a day later.
        Allowance next = caps.allowance(user, tomorrow);
        assertThat(next.used()).isEqualByComparingTo("0");
        assertThat(next.remaining()).isEqualByComparingTo("10");
        assertThat(next.resetsAt()).isEqualTo(startOfToday().plusSeconds(2 * 86_400));
        assertThatCode(() -> caps.requireAllowance(user, "parse_resume", tomorrow)).doesNotThrowAnyException();
    }

    @Test
    void usageFromYesterdayDoesNotCountToday() throws Exception {
        UUID user = newUser();
        ledger.record(usage(newKey(), user, "parse_resume", "0.05"), startOfToday().minusSeconds(60));

        assertThat(caps.allowance(user).used()).isEqualByComparingTo("0");
        assertThatCode(() -> caps.requireAllowance(user, "parse_resume")).doesNotThrowAnyException();
    }

    @CoversEndpoints({"GET /billing/allowance"})
    @Test
    void theAllowanceEndpointShowsTheCallersOwnFigures() throws Exception {
        Session session = newSessionFor(newEmailUser());
        UUID user = userIdOf(session.accessToken());
        ledger.record(usage(newKey(), user, "parse_resume", "0.004"));
        ledger.record(usage(newKey(), newUser(), "parse_resume", "0.009")); // someone else's spend

        mvc.perform(get("/billing/allowance").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCap").value(10))
                .andExpect(jsonPath("$.used").value(4))
                .andExpect(jsonPath("$.remaining").value(6))
                .andExpect(jsonPath("$.exhausted").value(false))
                .andExpect(jsonPath("$.resetsAt").value(startOfToday().plusSeconds(86_400).toString()));
    }

    @Test
    void theAllowanceEndpointNeedsASignedInUser() throws Exception {
        mvc.perform(get("/billing/allowance")).andExpect(status().isUnauthorized());
    }

    private String newEmailUser() throws Exception {
        return registerVerifiedUser();
    }

    private Session newSessionFor(String email) throws Exception {
        return login(email, PASSWORD, newIp());
    }

    private UUID userIdOf(String accessToken) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(payload, "$.sub"));
    }
}
