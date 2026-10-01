-- P3.1 AI usage ledger (PLAN.md section 5 billing; docs/adr/0025-ai-usage-ledger.md).

-- One row per billed AI provider call: resume parsing, resume and job embeddings, and every later feature.
--   request_key     idempotency key (ai-service's call id): UNIQUE, so a redelivered message or a retried write-back
--                   is recorded once. Recording is INSERT ... ON CONFLICT DO NOTHING on this key.
--   user_id         the user the call is attributed to; NULL for system work (job embeddings), or once the account is
--                   deleted (the cost history stays, the link to the person goes).
--   cost_micro_usd  the provider cost in millionths of a US dollar (an integer, so sums are exact). PLAN.md names the
--                   column cost_usd; the unit is the only difference.
--   pricing_version which version of ai-service's pinned price list produced the cost.
--   status          SUCCEEDED, or FAILED for a billed call whose output was discarded.
CREATE TABLE ai_calls (
    id              UUID         PRIMARY KEY,
    request_key     VARCHAR(100) NOT NULL,
    user_id         UUID         REFERENCES users (id) ON DELETE SET NULL,
    feature         VARCHAR(60)  NOT NULL,
    provider        VARCHAR(40)  NOT NULL,
    model           VARCHAR(100) NOT NULL,
    prompt_version  VARCHAR(100),
    pricing_version VARCHAR(40),
    input_tokens    BIGINT       NOT NULL DEFAULT 0,
    output_tokens   BIGINT       NOT NULL DEFAULT 0,
    cost_micro_usd  BIGINT       NOT NULL DEFAULT 0,
    latency_ms      BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ai_calls_request_key_key UNIQUE (request_key),
    CONSTRAINT ai_calls_status_check CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ai_calls_amounts_check CHECK (input_tokens >= 0 AND output_tokens >= 0 AND cost_micro_usd >= 0
        AND latency_ms >= 0)
);

-- The admin dashboard groups by day, feature and model over a date range.
CREATE INDEX ai_calls_created_idx ON ai_calls (created_at);
CREATE INDEX ai_calls_user_created_idx ON ai_calls (user_id, created_at) WHERE user_id IS NOT NULL;

-- Append-only per-user credit ledger. delta is in credits (a debit is negative); balance_after is the user's
-- running balance after this line. It is CACHED on the row rather than derived on read: lines are only ever
-- inserted, each insert takes a per-user lock and computes balance_after from the user's previous line, so the
-- chain is exact under concurrency. The balance starts at 0, and until plans grant credits (Phase 6) usage drives it
-- negative: the number is "credits consumed so far", and the daily cap, not the balance, is what limits spending.
CREATE TABLE credit_ledger (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    delta         NUMERIC(18,6) NOT NULL,
    reason        VARCHAR(30)   NOT NULL,
    ai_call_id    UUID          REFERENCES ai_calls (id),
    balance_after NUMERIC(18,6) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT credit_ledger_reason_check CHECK (reason IN ('AI_USAGE'))
);

-- A call is debited at most once, even if two writers race past the request-key check.
CREATE UNIQUE INDEX credit_ledger_ai_call_key ON credit_ledger (ai_call_id) WHERE reason = 'AI_USAGE';
CREATE INDEX credit_ledger_user_idx ON credit_ledger (user_id, id DESC);
CREATE INDEX credit_ledger_user_created_idx ON credit_ledger (user_id, created_at);

-- Lines are never changed. Deleting is still possible: account deletion removes a user's lines.
CREATE FUNCTION credit_ledger_reject_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'credit_ledger is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER credit_ledger_no_update BEFORE UPDATE ON credit_ledger
    FOR EACH ROW EXECUTE FUNCTION credit_ledger_reject_update();
