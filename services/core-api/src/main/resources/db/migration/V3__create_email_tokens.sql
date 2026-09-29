-- Single-use tokens emailed to users (verify address, reset password). Only the SHA-256
-- hash is stored; the raw token exists solely in the email.
CREATE TABLE email_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(30) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT email_tokens_type_check CHECK (type IN ('VERIFY_EMAIL', 'RESET_PASSWORD'))
);

CREATE INDEX email_tokens_user_type_idx ON email_tokens (user_id, type);
