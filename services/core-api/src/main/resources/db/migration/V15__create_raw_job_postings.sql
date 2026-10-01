-- Postings exactly as a source returned them, kept so they can be reprocessed when normalization
-- improves. One row per (source, external id); a refetch overwrites the payload and bumps
-- fetched_at. Retention (30 days) is a later task (P6.4); fetched_at is indexed for it.
CREATE TABLE raw_job_postings (
    id          UUID PRIMARY KEY,
    source_id   UUID         NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    target_id   UUID REFERENCES source_targets (id) ON DELETE SET NULL,
    external_id VARCHAR(255) NOT NULL,
    payload     JSONB        NOT NULL,
    fetched_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT raw_job_postings_external_uk UNIQUE (source_id, external_id)
);

CREATE INDEX raw_job_postings_fetched_at_idx ON raw_job_postings (fetched_at);
