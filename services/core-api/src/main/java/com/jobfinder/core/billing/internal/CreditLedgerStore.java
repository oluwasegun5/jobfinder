package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only writer of {@code credit_ledger} lines. Every line is appended under a per-user transaction lock
 * ({@code pg_advisory_xact_lock}, released with the surrounding transaction), so two writers for one user run one after
 * the other and each computes {@code balance_after} from the line before it; writers for different users never wait
 * for each other. Lines are never updated (a trigger rejects it).
 *
 * <p>Two figures ride on each line. {@code balance_after} is the balance. {@code topup_after} is the part of it that
 * comes from top-ups and never expires: a top-up raises it, and everything else lowers it only when the balance falls
 * below it (spending uses plan credits first). The rest of the balance, {@code balance - topup}, is the plan portion
 * that a monthly grant may expire.
 */
@Component
class CreditLedgerStore {

    /** The end of the chain: the balance, and the never-expiring part of it. */
    record Position(BigDecimal balance, BigDecimal topup) {

        /** The credits that came from a plan and have not been spent (never negative). */
        BigDecimal planPortion() {
            return balance.subtract(topup).max(BigDecimal.ZERO);
        }
    }

    private final JdbcClient jdbc;

    CreditLedgerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes the user's ledger lock for the rest of the transaction (reentrant within it). */
    void lock(UUID userId) {
        jdbc.sql("select count(*) from (select pg_advisory_xact_lock(hashtextextended(:user, 0))) l")
                .param("user", userId.toString()).query(Long.class).single();
    }

    Position position(UUID userId) {
        return jdbc.sql("""
                select coalesce((select balance_after from credit_ledger where user_id = :userId
                                  order by id desc limit 1), 0) as balance,
                       coalesce((select topup_after from credit_ledger where user_id = :userId
                                  order by id desc limit 1), 0) as topup
                """)
                .param("userId", userId)
                .query((rs, row) -> new Position(rs.getBigDecimal("balance"), rs.getBigDecimal("topup")))
                .single();
    }

    boolean userExists(UUID userId) {
        return jdbc.sql("select exists (select 1 from users where id = :u)").param("u", userId)
                .query(Boolean.class).single();
    }

    BigDecimal balance(UUID userId) {
        return position(userId).balance();
    }

    boolean keyExists(String idempotencyKey) {
        return jdbc.sql("select exists (select 1 from credit_ledger where idempotency_key = :key)")
                .param("key", idempotencyKey).query(Boolean.class).single();
    }

    /**
     * Appends one line and returns true, or returns false (and writes nothing) when {@code idempotencyKey} is already
     * in the ledger or the user no longer exists. {@code aiCallId} is set for {@link LedgerReason#AI_USAGE} only; {@code idempotencyKey} is required
     * for grants, expiries and top-ups and may be null for usage (which has its own once-per-call guard).
     */
    boolean append(UUID userId, BigDecimal delta, LedgerReason reason, UUID aiCallId, String idempotencyKey,
            Instant at) {
        lock(userId);
        var statement = jdbc.sql("""
                with prev as (
                    select coalesce((select balance_after from credit_ledger where user_id = :userId
                                      order by id desc limit 1), 0) as balance,
                           coalesce((select topup_after from credit_ledger where user_id = :userId
                                      order by id desc limit 1), 0) as topup),
                     next as (select balance + :delta as balance, topup from prev)
                insert into credit_ledger (user_id, delta, reason, ai_call_id, idempotency_key, balance_after,
                                           topup_after, created_at)
                select :userId, :delta, cast(:reason as varchar), cast(:callId as uuid), cast(:key as varchar),
                       next.balance,
                       case when cast(:reason as varchar) = 'TOPUP' then next.topup + :delta
                            else least(next.topup, greatest(next.balance, 0)) end,
                       :at
                  from next
                 where exists (select 1 from users where id = :userId)
                on conflict (idempotency_key) do nothing
                """)
                .param("userId", userId)
                .param("delta", delta)
                .param("reason", reason.name())
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
        statement = aiCallId == null ? statement.param("callId", null, Types.VARCHAR)
                : statement.param("callId", aiCallId.toString());
        statement = idempotencyKey == null ? statement.param("key", null, Types.VARCHAR)
                : statement.param("key", idempotencyKey);
        return statement.update() == 1;
    }
}
