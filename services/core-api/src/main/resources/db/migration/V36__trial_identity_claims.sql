-- G1: staged trial credits and the duplicate-account gate (docs/adr/0042-trial-credit-abuse-gate.md).

-- 1. Trial-eligibility claims. Keyed digests only (HMAC-SHA-256 under a pepper held outside the database); no raw email,
--    phone, Google id or file. Unlinked (user_id set to NULL), not deleted, when the account is deleted, so that the same
--    person cannot claim the trial again; purged 12 months after last_seen_at (expires_at). The one documented exception
--    to "deletion erases everything" (ADR 0040, amended by ADR 0042).
--    claimant_count: how many different users were granted a milestone with this value (one row per value, so the count
--    tells a CV file of one prior claimant, a hard match, from a shared template, a soft one).
CREATE TABLE trial_identity_claims (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    kind               VARCHAR(16) NOT NULL CHECK (kind IN ('EMAIL', 'GOOGLE_SUB', 'CV_FILE', 'CV_EMAIL', 'CV_PHONE')),
    digest             BYTEA       NOT NULL CHECK (octet_length(digest) = 32),
    key_version        SMALLINT    NOT NULL CHECK (key_version >= 1),
    user_id            UUID        NULL REFERENCES users (id) ON DELETE SET NULL,
    claimed_milestones INTEGER     NOT NULL DEFAULT 0 CHECK (claimed_milestones BETWEEN 0 AND 15),
    claimant_count     INTEGER     NOT NULL DEFAULT 1 CHECK (claimant_count >= 1),
    first_claimed_at   TIMESTAMPTZ NOT NULL,
    last_seen_at       TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT trial_identity_claims_key UNIQUE (kind, digest, key_version)
);
CREATE INDEX trial_identity_claims_digest_idx  ON trial_identity_claims (digest);
CREATE INDEX trial_identity_claims_user_idx    ON trial_identity_claims (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX trial_identity_claims_expires_idx ON trial_identity_claims (expires_at);

-- 2. One row per user on the trial path: the gate's decision, the milestones unlocked, the shadow-mode risk score, M1
--    time and the trial's expiry. No row: the user has not reached M1 (PENDING). LEGACY: the account existed before the
--    trial (it had the monthly free grant) and gets no trial. Deleted with the account.
CREATE TABLE trial_accounts (
    user_id     UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    state       VARCHAR(10) NOT NULL CHECK (state IN ('GRANTED', 'HOLD', 'DENIED', 'LEGACY')),
    milestones  INTEGER     NOT NULL DEFAULT 0 CHECK (milestones BETWEEN 0 AND 15),
    risk_score  INTEGER     NOT NULL DEFAULT 0,
    started_at  TIMESTAMPTZ NULL,
    expires_at  TIMESTAMPTZ NULL,
    ended_at    TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX trial_accounts_expiry_idx ON trial_accounts (expires_at)
    WHERE state IN ('GRANTED', 'HOLD') AND ended_at IS NULL;

-- 3. Accounts verified before this point existed before the trial: a startup job (Flyway cannot see the peppers) writes
--    their claims, so that deleting and re-creating them cannot obtain a trial either.
CREATE TABLE trial_seed (
    id           SMALLINT    PRIMARY KEY CHECK (id = 1),
    cutoff       TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL
);
INSERT INTO trial_seed (id, cutoff) VALUES (1, now());

-- 4. The last good copy of the disposable-email-domain list (refreshed weekly when enabled; the copy shipped in the jar
--    is used until the first refresh). Domain names only.
CREATE TABLE disposable_domain_list (
    id           SMALLINT     PRIMARY KEY CHECK (id = 1),
    domains      TEXT         NOT NULL,
    domain_count INTEGER      NOT NULL CHECK (domain_count > 0),
    source       VARCHAR(500) NOT NULL,
    fetched_at   TIMESTAMPTZ  NOT NULL
);

-- 5. The SHA-256 of an uploaded CV, taken while the upload is in memory, so the parse step can digest it without reading
--    the file again. Not exported (a content hash says nothing to the user) and deleted with the CV.
ALTER TABLE resumes ADD COLUMN content_sha256 BYTEA NULL CHECK (octet_length(content_sha256) = 32);

-- 6. Ledger reasons for the trial: TRIAL_GRANT (positive) and TRIAL_EXPIRY (negative), both under an idempotency key.
ALTER TABLE credit_ledger DROP CONSTRAINT credit_ledger_reason_check;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_reason_check
    CHECK (reason IN ('AI_USAGE', 'PLAN_GRANT', 'PLAN_EXPIRY', 'TOPUP', 'REFUND_ADJUSTMENT', 'TRIAL_GRANT',
                      'TRIAL_EXPIRY'));
ALTER TABLE credit_ledger DROP CONSTRAINT credit_ledger_sign_check;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_sign_check
    CHECK ((reason IN ('PLAN_GRANT', 'TOPUP', 'TRIAL_GRANT') AND delta > 0)
        OR (reason IN ('AI_USAGE', 'PLAN_EXPIRY', 'TRIAL_EXPIRY') AND delta < 0)
        OR reason = 'REFUND_ADJUSTMENT');
-- The gate's circuit breaker counts the trial grants of the last hour.
CREATE INDEX credit_ledger_trial_grant_created_idx ON credit_ledger (created_at) WHERE reason = 'TRIAL_GRANT';
ALTER TABLE credit_ledger DROP CONSTRAINT credit_ledger_key_check;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_key_check
    CHECK (reason NOT IN ('PLAN_GRANT', 'PLAN_EXPIRY', 'TOPUP', 'TRIAL_GRANT', 'TRIAL_EXPIRY')
        OR idempotency_key IS NOT NULL);
