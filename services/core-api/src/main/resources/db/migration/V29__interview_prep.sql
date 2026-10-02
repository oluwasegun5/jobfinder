-- P5.1 interview prep (PLAN.md section 5 interview; docs/adr/0033-interview-prep.md): the likely questions for a job
-- and a company brief built only from the job posting and the company record. Backward compatible: three new tables,
-- nothing existing is touched.
--
-- interview_prep: one row per (user, job, prompt version). The unique constraint is the idempotency key: a repeated
-- request finds the row instead of calling the model again, and its leading columns (user_id, job_id) are the index
-- every lookup by user and job uses.
--   status          GENERATING (placeholder while ai-service works: it also makes a double click one model call) or
--                   READY. A failed generation deletes its placeholder, so a retry starts clean.
--   job_id          provenance only, deliberately WITHOUT a foreign key: a prep outlives the job being expired or
--                   deleted. job_title and job_company are copied so it still says what it was made for.
--   model           the model that wrote the questions; brief_model the one that wrote the brief.
--   questions_dropped  questions ai-service removed (repeats, text copied from an injected instruction).
-- interview_questions: the questions of a prep, in order (position), each with its category and difficulty.
-- company_briefs: at most one per prep. `sections` is [{id, title, claims: [{statement, source, evidence}]}] where
--   `source` names the job or company field a claim came from and `evidence` quotes it; `unknowns` is what the
--   posting and the company record do not say; `dropped_claims` counts claims the grounding check removed.
CREATE TABLE interview_prep (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id            UUID         NOT NULL,
    job_title         VARCHAR(400) NOT NULL,
    job_company       VARCHAR(400),
    status            VARCHAR(20)  NOT NULL,
    prompt_version    VARCHAR(100) NOT NULL,
    model             VARCHAR(100),
    brief_model       VARCHAR(100),
    questions_dropped INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT interview_prep_status_check CHECK (status IN ('GENERATING', 'READY')),
    CONSTRAINT interview_prep_ready_check CHECK (status = 'GENERATING' OR model IS NOT NULL),
    CONSTRAINT interview_prep_user_job_prompt_key UNIQUE (user_id, job_id, prompt_version)
);

CREATE TABLE interview_questions (
    id         UUID         PRIMARY KEY,
    prep_id    UUID         NOT NULL REFERENCES interview_prep (id) ON DELETE CASCADE,
    position   SMALLINT     NOT NULL,
    category   VARCHAR(20)  NOT NULL,
    question   VARCHAR(300) NOT NULL,
    rationale  VARCHAR(300) NOT NULL,
    difficulty VARCHAR(10)  NOT NULL,
    CONSTRAINT interview_questions_category_check CHECK (category IN ('behavioral', 'technical', 'role_specific')),
    CONSTRAINT interview_questions_difficulty_check CHECK (difficulty IN ('easy', 'medium', 'hard')),
    CONSTRAINT interview_questions_position_key UNIQUE (prep_id, position)
);

CREATE TABLE company_briefs (
    id             UUID         PRIMARY KEY,
    prep_id        UUID         NOT NULL REFERENCES interview_prep (id) ON DELETE CASCADE,
    model          VARCHAR(100),
    sections       JSONB        NOT NULL,
    unknowns       JSONB        NOT NULL,
    dropped_claims INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT company_briefs_prep_key UNIQUE (prep_id)
);
