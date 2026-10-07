-- P6.1 plans, credits and payments (docs/adr/0036-plans-credits-and-payments.md).
-- V22 is untouched: the ledger gains columns and a wider reason list here, and its append-only trigger still holds.

-- Plans. Free and Pro. monthly_credits is the seeded default; app.billing.plans.<code>.monthly-credits overrides it.
CREATE TABLE plans (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(30)   NOT NULL UNIQUE,
    name            VARCHAR(100)  NOT NULL,
    monthly_credits NUMERIC(18,6) NOT NULL CHECK (monthly_credits >= 0),
    active          BOOLEAN       NOT NULL DEFAULT TRUE
);

INSERT INTO plans (code, name, monthly_credits, active) VALUES
    ('free', 'Free', 300, TRUE),
    ('pro',  'Pro',  6000, TRUE);

-- Ledger: more reasons, an idempotency key for grants and top-ups, and the part of the balance that never expires.
ALTER TABLE credit_ledger DROP CONSTRAINT credit_ledger_reason_check;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_reason_check
    CHECK (reason IN ('AI_USAGE', 'PLAN_GRANT', 'PLAN_EXPIRY', 'TOPUP', 'REFUND_ADJUSTMENT'));

ALTER TABLE credit_ledger ADD COLUMN idempotency_key VARCHAR(200);
-- Existing AI_USAGE lines have 0: top-ups did not exist when they were written.
ALTER TABLE credit_ledger ADD COLUMN topup_after NUMERIC(18,6) NOT NULL DEFAULT 0;

-- A grant, an expiry or a top-up with the same key is written once, however often the webhook or job repeats.
CREATE UNIQUE INDEX credit_ledger_idempotency_key ON credit_ledger (idempotency_key);

ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_sign_check
    CHECK ((reason IN ('PLAN_GRANT', 'TOPUP') AND delta > 0)
        OR (reason IN ('AI_USAGE', 'PLAN_EXPIRY') AND delta < 0)
        OR reason = 'REFUND_ADJUSTMENT');
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_key_check
    CHECK (reason NOT IN ('PLAN_GRANT', 'PLAN_EXPIRY', 'TOPUP') OR idempotency_key IS NOT NULL);

-- Subscriptions (paid plans only; a user with no ACTIVE or PAST_DUE row is on Free).
CREATE TABLE subscriptions (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_id              UUID         NOT NULL REFERENCES plans (id),
    provider             VARCHAR(10)  NOT NULL CHECK (provider IN ('STRIPE', 'PAYSTACK')),
    provider_ref         VARCHAR(200),
    provider_customer    VARCHAR(200),
    status               VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'PAST_DUE', 'CANCELED')),
    current_period_end   TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN      NOT NULL DEFAULT FALSE,
    past_due_since       TIMESTAMPTZ,
    -- Provider time of the newest event applied; an older event never overwrites what a newer one set.
    last_event_at        TIMESTAMPTZ  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX subscriptions_provider_ref_key ON subscriptions (provider, provider_ref)
    WHERE provider_ref IS NOT NULL;
-- One paid subscription per user.
CREATE UNIQUE INDEX subscriptions_one_live_per_user ON subscriptions (user_id) WHERE status IN ('ACTIVE', 'PAST_DUE');
CREATE INDEX subscriptions_user_idx ON subscriptions (user_id, created_at DESC);
CREATE INDEX subscriptions_customer_idx ON subscriptions (provider, provider_customer)
    WHERE provider_customer IS NOT NULL;
CREATE INDEX subscriptions_due_idx ON subscriptions (current_period_end) WHERE status IN ('ACTIVE', 'PAST_DUE');

-- Webhook deliveries already handled. Provider ids only: no payload, no personal data.
CREATE TABLE webhook_events (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider    VARCHAR(10)  NOT NULL,
    event_id    VARCHAR(200) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT webhook_events_provider_event_key UNIQUE (provider, event_id)
);

-- Remote subscriptions that could not be cancelled when an account was deleted, kept (provider ids only) for retry.
CREATE TABLE remote_cancellations (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider        VARCHAR(10)  NOT NULL,
    provider_ref    VARCHAR(200) NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT remote_cancellations_ref_key UNIQUE (provider, provider_ref)
);
