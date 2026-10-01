-- P3.2 matching engine (docs/adr/0026-matching-engine.md).
--
-- match_scores: the model's score for one job against one resume version, cached. The key is
-- (resume_version_id, job_id, prompt_version): a new primary resume version, or a new prompt version, is a new key,
-- so scores of an old resume are never shown for a new one. resume_hash and job_hash are SHA-256 of the exact
-- snapshots the model was shown (the resume content and the job content, in the form sent to ai-service); a row is
-- valid only while both equal the current hashes, so editing the resume in place or a changed job posting makes the
-- row stale without a new key, and the next scoring rewrites it (upsert on the key). Only model-scored jobs are
-- stored; fallbacks (stage-2 only) are recomputed on every read because they are cheap.
--
-- The stage-2 components stored beside the score are those at the moment the model scored the job: the cosine
-- similarity of the two embeddings (null when unknown), the share of the job's listed skills the resume has (null
-- when either side lists none), the recency component (0 to 1) and their blend on a 0 to 100 scale.
CREATE TABLE match_scores (
    id                UUID PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    resume_version_id UUID         NOT NULL REFERENCES resume_versions (id) ON DELETE CASCADE,
    job_id            UUID         NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    prompt_version    VARCHAR(100) NOT NULL,
    resume_hash       CHAR(64)     NOT NULL,
    job_hash          CHAR(64)     NOT NULL,
    vector_score      NUMERIC(7, 6),
    skill_overlap     NUMERIC(7, 6),
    recency_score     NUMERIC(7, 6) NOT NULL,
    stage2_score      NUMERIC(6, 3) NOT NULL,
    llm_score         SMALLINT     NOT NULL,
    strengths         TEXT[]       NOT NULL DEFAULT '{}',
    gaps              TEXT[]       NOT NULL DEFAULT '{}',
    model             VARCHAR(100) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    computed_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT match_scores_key_uk UNIQUE (resume_version_id, job_id, prompt_version),
    CONSTRAINT match_scores_llm_score_check CHECK (llm_score BETWEEN 0 AND 100),
    CONSTRAINT match_scores_stage2_check CHECK (stage2_score BETWEEN 0 AND 100)
);

-- A user's best scores (the feed), and the pruning pass that looks at each user's newest rows.
CREATE INDEX match_scores_user_score_idx ON match_scores (user_id, llm_score DESC);
CREATE INDEX match_scores_user_computed_idx ON match_scores (user_id, computed_at DESC);
CREATE INDEX match_scores_job_idx ON match_scores (job_id);
CREATE INDEX match_scores_computed_idx ON match_scores (computed_at);

-- One row per nightly run: what it did and why it stopped. Counts are users except where named.
CREATE TABLE match_batch_runs (
    id              UUID PRIMARY KEY,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL,
    stop_reason     VARCHAR(40),
    users_considered INTEGER    NOT NULL DEFAULT 0,
    users_matched   INTEGER     NOT NULL DEFAULT 0,
    users_skipped   INTEGER     NOT NULL DEFAULT 0,
    users_failed    INTEGER     NOT NULL DEFAULT 0,
    users_capped    INTEGER     NOT NULL DEFAULT 0,
    jobs_llm_scored INTEGER     NOT NULL DEFAULT 0,
    jobs_cached     INTEGER     NOT NULL DEFAULT 0,
    ai_requests     INTEGER     NOT NULL DEFAULT 0,
    scores_pruned   INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT match_batch_runs_status_check CHECK (status IN ('RUNNING', 'COMPLETED', 'STOPPED', 'FAILED'))
);

CREATE INDEX match_batch_runs_started_idx ON match_batch_runs (started_at DESC);
