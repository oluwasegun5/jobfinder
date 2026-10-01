-- P2.7 admin source dashboard and alerts (docs/adr/0024-source-dashboard-alerts.md).

-- How many targets a run attempted. An error rate needs the denominator, and a "ran fine but fetched nothing"
-- alert needs to know the run really had something to fetch. Runs written before this migration keep 0, which
-- the alert rules read as "unknown" and never evaluate.
ALTER TABLE ingestion_runs ADD COLUMN targets INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ingestion_runs ADD CONSTRAINT ingestion_runs_targets_check CHECK (targets >= 0);

-- The run history lists every source's runs, newest first.
CREATE INDEX ingestion_runs_started_idx ON ingestion_runs (started_at DESC);

-- One row per (source, rule): the state of that alert. active is true from the run that first breached the rule
-- until a run that no longer does, so a source that stays broken is announced once (plus an optional re-alert
-- after an interval), not on every run. A new episode overwrites the row of the resolved one.
CREATE TABLE source_alerts (
    source_id        UUID         NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    rule             VARCHAR(30)  NOT NULL,
    active           BOOLEAN      NOT NULL,
    first_fired_at   TIMESTAMPTZ  NOT NULL,
    last_notified_at TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    occurrences      INTEGER      NOT NULL DEFAULT 1,
    notifications    INTEGER      NOT NULL DEFAULT 0,
    last_run_id      UUID,
    detail           TEXT,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (source_id, rule),
    CONSTRAINT source_alerts_rule_check CHECK (rule IN ('ZERO_JOBS', 'ERROR_RATE')),
    CONSTRAINT source_alerts_counts_check CHECK (occurrences >= 1 AND notifications >= 0)
);
