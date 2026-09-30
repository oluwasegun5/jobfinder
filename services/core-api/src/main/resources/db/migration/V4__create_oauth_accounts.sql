-- External identities (Google) linked to a user. One user may have several; a provider
-- account belongs to exactly one user.
CREATE TABLE oauth_accounts (
    id               UUID PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider         VARCHAR(20)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT oauth_accounts_provider_check CHECK (provider IN ('GOOGLE')),
    CONSTRAINT oauth_accounts_provider_user_uk UNIQUE (provider, provider_user_id)
);

CREATE INDEX oauth_accounts_user_id_idx ON oauth_accounts (user_id);
