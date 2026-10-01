-- P2.2: what the normalizer, the deduplicator and the expiry rules need on top of the P2.1 tables.
--
-- job_sources becomes the record of "this source listed this job": which target it came from (so a
-- run can tell which listings its fetch should have returned), when it was last seen, and for how
-- many consecutive successful runs of a full-listing source it was missing. Existing rows (there are
-- none yet: nothing wrote to jobs before P2.2) start as seen now, missing zero times.
ALTER TABLE job_sources
    ADD COLUMN target_id    UUID REFERENCES source_targets (id) ON DELETE SET NULL,
    ADD COLUMN last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN missed_runs  INTEGER     NOT NULL DEFAULT 0,
    ADD CONSTRAINT job_sources_missed_runs_check CHECK (missed_runs >= 0);

CREATE INDEX job_sources_source_target_idx ON job_sources (source_id, target_id);

-- The value sets the normalizer produces (ADR 0019). They were left open in V16 until the normalizer
-- fixed them; the tables are still empty, so adding the constraints cannot fail on existing data.
ALTER TABLE jobs
    ADD CONSTRAINT jobs_work_mode_check CHECK (work_mode IS NULL OR work_mode IN ('REMOTE', 'HYBRID', 'ONSITE')),
    ADD CONSTRAINT jobs_employment_type_check CHECK (employment_type IS NULL
        OR employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'TEMPORARY', 'INTERNSHIP')),
    ADD CONSTRAINT jobs_seniority_check CHECK (seniority IS NULL
        OR seniority IN ('INTERN', 'JUNIOR', 'MID', 'SENIOR', 'LEAD', 'EXECUTIVE')),
    ADD CONSTRAINT jobs_salary_period_check CHECK (salary_period IS NULL
        OR salary_period IN ('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR'));
