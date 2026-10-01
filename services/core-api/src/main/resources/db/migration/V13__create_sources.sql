-- A job source (GREENHOUSE, ADZUNA, ...). One row per adapter, registered on startup. config holds
-- per-source tuning only (schedule interval, jitter, rate limit, retry attempts); it never holds
-- secrets: API keys come from the environment.
CREATE TABLE sources (
    id          UUID PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    kind        VARCHAR(20)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    config      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_run_at TIMESTAMPTZ,
    health      VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT sources_kind_check CHECK (kind IN ('ATS', 'AGGREGATOR', 'SCRAPE')),
    CONSTRAINT sources_health_check CHECK (health IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'FAILING'))
);

-- What a source is asked to fetch: a Greenhouse board token, a Lever company slug, an aggregator
-- search. One fetch call per enabled target per run.
CREATE TABLE source_targets (
    id          UUID PRIMARY KEY,
    source_id   UUID         NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    identifier  VARCHAR(255) NOT NULL,
    company_id  UUID REFERENCES companies (id) ON DELETE SET NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT source_targets_identifier_uk UNIQUE (source_id, identifier)
);
