-- Employers that jobs belong to. Filled by the normalizer (P2.2) and by source targets that name
-- their company up front. normalized_name is an ordinary index, not a unique key: how companies are
-- matched and merged is decided when the normalizer is written.
CREATE TABLE companies (
    id              UUID PRIMARY KEY,
    name            VARCHAR(300) NOT NULL,
    normalized_name VARCHAR(300) NOT NULL,
    domain          VARCHAR(255),
    logo_url        VARCHAR(1000),
    size            VARCHAR(50),
    industry        VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX companies_normalized_name_idx ON companies (normalized_name);
