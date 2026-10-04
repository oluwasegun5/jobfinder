package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.billing.AiUsage;

/** The writer of {@code ai_calls} (its debits go through {@link CreditLedgerStore}) and the reader of what a user spent. */
@Component
class AiCallStore {

    /** A newly written call. {@code userId} is null when the call has no owner, or its owner no longer exists. */
    record Inserted(UUID userId) {
    }

    private final JdbcClient jdbc;
    private final CreditLedgerStore ledger;

    AiCallStore(JdbcClient jdbc, CreditLedgerStore ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /**
     * Writes the call unless its request key was already recorded (then the result is empty). The owner is looked up
     * in the same statement, so usage of an account that has just been deleted is still recorded, without an owner,
     * instead of failing on the foreign key.
     */
    Optional<Inserted> insertCall(UUID id, AiUsage usage, long costMicroUsd, Instant at) {
        var statement = jdbc.sql("""
                insert into ai_calls (id, request_key, user_id, feature, provider, model, prompt_version,
                                      pricing_version, input_tokens, output_tokens, cost_micro_usd, latency_ms,
                                      status, created_at)
                values (:id, :key, (select id from users where id = :userId), :feature, :provider, :model,
                        :promptVersion, :pricingVersion, :inputTokens, :outputTokens, :cost, :latency, :status,
                        :at)
                on conflict (request_key) do nothing
                returning user_id
                """)
                .param("id", id)
                .param("key", usage.requestKey())
                .param("feature", usage.feature())
                .param("provider", usage.provider())
                .param("model", usage.model())
                .param("promptVersion", usage.promptVersion())
                .param("pricingVersion", usage.pricingVersion())
                .param("inputTokens", usage.inputTokens())
                .param("outputTokens", usage.outputTokens())
                .param("cost", costMicroUsd)
                .param("latency", usage.latencyMs())
                .param("status", usage.status().name())
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
        statement = usage.userId() == null ? statement.param("userId", null, Types.OTHER)
                : statement.param("userId", usage.userId());
        return statement.query((rs, row) -> new Inserted(rs.getObject("user_id", UUID.class))).optional();
    }

    /**
     * Appends a debit of {@code credits} (a positive amount) to the user's ledger, under the per-user lock the ledger
     * store takes (see {@link CreditLedgerStore}); the lock is released with the surrounding transaction.
     */
    void debit(UUID userId, UUID aiCallId, BigDecimal credits, Instant at) {
        ledger.append(userId, credits.negate(), LedgerReason.AI_USAGE, aiCallId, null, at);
    }

    /** Credits the user's AI calls consumed in {@code [from, to)}. */
    BigDecimal creditsUsed(UUID userId, Instant from, Instant to) {
        return jdbc.sql("""
                select coalesce(-sum(delta), 0) from credit_ledger
                 where user_id = :userId and reason = 'AI_USAGE' and created_at >= :from and created_at < :to
                """)
                .param("userId", userId)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query(BigDecimal.class).single();
    }
}
