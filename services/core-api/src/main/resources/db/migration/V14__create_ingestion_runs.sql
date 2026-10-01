-- One row per ingestion run of a source, written when the run starts (status RUNNING) and closed
-- when it ends. Counts are per run. Until the normalizer exists (P2.2), created and updated count
-- raw postings stored for the first time and refreshed; expired stays 0. status and error_summary
-- are additions to the PLAN.md columns: an alert and a dashboard need to tell a failed run from a
-- quiet one.
CREATE TABLE ingestion_runs (
    id            UUID PRIMARY KEY,
    source_id     UUID         NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    started_at    TIMESTAMPTZ  NOT NULL,
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    fetched       INTEGER      NOT NULL DEFAULT 0,
    created       INTEGER      NOT NULL DEFAULT 0,
    updated       INTEGER      NOT NULL DEFAULT 0,
    expired       INTEGER      NOT NULL DEFAULT 0,
    errors        INTEGER      NOT NULL DEFAULT 0,
    error_summary TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ingestion_runs_status_check CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ingestion_runs_counts_check CHECK (fetched >= 0 AND created >= 0 AND updated >= 0
        AND expired >= 0 AND errors >= 0)
);

CREATE INDEX ingestion_runs_source_started_idx ON ingestion_runs (source_id, started_at DESC);
