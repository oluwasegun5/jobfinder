package com.jobfinder.core.billing.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;

import com.jobfinder.core.activation.DigestSet;
import com.jobfinder.core.activation.EmailVerified;
import com.jobfinder.core.activation.IdentityDigester;
import com.jobfinder.core.activation.IdentityNormaliser;
import com.jobfinder.core.activation.ResumeParsed;

/**
 * The trial gate as production runs it: trial on, monthly free grant off, score in shadow mode. One property set for
 * every trial suite, so they share one Spring context.
 */
@TestPropertySource(properties = {
        "app.billing.trial.enabled=true",
        "app.billing.jobs.free-grant-enabled=false",
        "app.billing.trial.score-enforced=false"})
abstract class TrialTestSupport extends BillingTestSupport {

    @Autowired
    protected IdentityDigester digester;

    @Autowired
    protected ApplicationEventPublisher events;

    /** Signs up with {@code email} and verifies through the real link (milestone M1); returns the user id. */
    protected UUID signUpAndVerify(String email) throws Exception {
        int before = mailsTo(email).size();
        postJson("/auth/signup", credentials(email, PASSWORD), newIp()).andReturn();
        List<String> mails = awaitMails(email, before + 1);
        String token = extractToken(mails.get(0));
        postJson("/auth/verify-email", "{\"token\":\"%s\"}".formatted(token), newIp()).andReturn();
        return userId(email);
    }

    protected UUID userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    /** Deletes the account through {@code DELETE /me}, as the user would. */
    protected void deleteAccount(String email) throws Exception {
        Session session = login(email, PASSWORD, newIp());
        mvc.perform(delete("/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());
    }

    /** A verified, consenting user that has not reached any milestone (no event was published for it). */
    protected UUID bareVerifiedUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, email_verified_at, created_at, updated_at, ai_consent_version, ai_consent_at)
                values (?, ?, now(), now(), now(), '1', now())
                """, id, "bare-" + id + "@example.test");
        return id;
    }

    protected DigestSet emailDigest(String email) {
        return digester.email(email).orElseThrow();
    }

    protected DigestSet fileDigest(String content) {
        return digester.file(IdentityNormaliser.fileSha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Milestone M1 for a bare user, as identity would publish it. */
    protected void m1(UUID userId, String email, Instant at) {
        events.publishEvent(new EmailVerified(userId, emailDigest(email), IdentityNormaliser.emailDomain(email)
                .orElseThrow(), null, at));
    }

    protected void m2(UUID userId, DigestSet file, List<DigestSet> cvEmails, List<DigestSet> cvPhones, Instant at) {
        events.publishEvent(new ResumeParsed(userId, UUID.randomUUID(), file, cvEmails, cvPhones, at));
    }

    protected String trialState(UUID userId) {
        List<String> states = jdbc.queryForList("select state from trial_accounts where user_id = ?", String.class,
                userId);
        return states.isEmpty() ? null : states.get(0);
    }

    protected int riskScore(UUID userId) {
        return jdbc.queryForObject("select risk_score from trial_accounts where user_id = ?", Integer.class, userId);
    }

    protected BigDecimal trialGranted(UUID userId) {
        return jdbc.queryForObject("select coalesce(sum(delta), 0) from credit_ledger where user_id = ? "
                + "and reason = 'TRIAL_GRANT'", BigDecimal.class, userId);
    }

    protected static String uniqueGmail(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@gmail.com";
    }
}
