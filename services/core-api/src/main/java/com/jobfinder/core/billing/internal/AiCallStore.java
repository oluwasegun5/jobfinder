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

/** The only writer of {@code ai_calls} and {@code credit_ledger}, and the reader of what a user spent. */
@Component
class AiCallStore {

    /** A newly written call. {@code userId} is null when the call has no owner, or its owner no longer exists. */
    record Inserted(UUID userId) {
    }

    private final JdbcClient jdbc;

    AiCallStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
     * Appends a debit of {@code credits} (a positive amount) to the user's ledger. Takes a per-user transaction lock
     * first, so two debits for one user run one after the other and each computes its balance from the line before
     * it; the lock is released with the surrounding transaction. Debits of different users do not wait on each other.
     */
    void debit(UUID userId, UUID aiCallId, BigDecimal credits, Instant at) {
        jdbc.sql("select count(*) from (select pg_advisory_xact_lock(hashtextextended(:user, 0))) l")
                .param("user", userId.toString()).query(Long.class).single();
        jdbc.sql("""
                insert into credit_ledger (user_id, delta, reason, ai_call_id, balance_after, created_at)
                values (:userId, :delta, 'AI_USAGE', :callId,
                        coalesce((select balance_after from credit_ledger where user_id = :userId
                                   order by id desc limit 1), 0) + :delta,
                        :at)
                """)
                .param("userId", userId)
                .param("delta", credits.negate())
                .param("callId", aiCallId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
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
