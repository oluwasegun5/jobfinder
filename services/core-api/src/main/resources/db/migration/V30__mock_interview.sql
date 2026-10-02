-- P5.2 mock interview (PLAN.md section 5 interview; docs/adr/0034-mock-interview.md): text mock interview sessions, the
-- turns of each, and the rubric feedback on every answer. Backward compatible: two new tables, nothing existing is
-- touched (credit_ledger and its reason check least of all: credits per session are read off the existing per-call
-- ledger, see the ADR).
--
-- interview_sessions: one mock interview.
--   job_id, prep_id, application_id  provenance only, plain UUIDs with no foreign key (as interview_prep.job_id and the
--                   application tables have none: a session outlives a job that expires, a deleted application or a prep).
--                   job_title and job_company are copied so a session still says what it was for.
--   mode            fixed to MOCK; the column exists because PLAN.md models QUESTIONS and MOCK on one table.
--   persona         the interviewer, chosen once at start from the job (closed vocabularies, see PersonaPicker).
--   status          ACTIVE, COMPLETED or ABANDONED (no activity for app.interview.mock.abandon-after). Both end states
--                   are final: no answers after COMPLETED, an ABANDONED session cannot be resumed.
--   max_turns       the limit this session was started with (from config, never above it); turns_answered counts the
--                   candidate turns stored.
--   credits_consumed  credits the session's recorded AI calls cost, accumulated from the usage ledger outcomes (calls that
--                   were billed and then discarded count too). Never the cap, never a balance: a figure to show.
--   summary         the session summary (averages, narrative, next steps), set when it completes.
--   in_flight_since, in_flight_key  the single in-flight request of the session (an answer or the completion): taking it
--                   is one conditional UPDATE, so a second concurrent request finds it taken. A holder that died is
--                   replaced once in_flight_since is older than the configured timeout.
--   last_activity_at  when the session last moved; the abandonment clock.
-- interview_turns: the transcript, in order. position 0 is the opening question, then candidate, interviewer, candidate...
--   (UNIQUE (session_id, position) makes the order, and a double write of the same turn, impossible).
--   category / question_source  of an interviewer turn (behavioral, technical or role_specific; PREP or GENERATED).
--   feedback        the rubric feedback of a candidate turn (jsonb).
--   idempotency_key the client's key of a candidate turn: unique per session, so a repeated submission finds the stored
--                   turn instead of calling the model again.
CREATE TABLE interview_sessions (
    id               UUID          PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id           UUID          NOT NULL,
    job_title        VARCHAR(400)  NOT NULL,
    job_company      VARCHAR(400),
    application_id   UUID,
    prep_id          UUID,
    mode             VARCHAR(20)   NOT NULL DEFAULT 'MOCK',
    persona          JSONB         NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    max_turns        SMALLINT      NOT NULL,
    turns_answered   SMALLINT      NOT NULL DEFAULT 0,
    credits_consumed NUMERIC(14, 6) NOT NULL DEFAULT 0,
    prompt_version   VARCHAR(100)  NOT NULL,
    summary          JSONB,
    in_flight_since  TIMESTAMPTZ,
    in_flight_key    VARCHAR(100),
    last_activity_at TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    completed_at     TIMESTAMPTZ,
    CONSTRAINT interview_sessions_mode_check CHECK (mode = 'MOCK'),
    CONSTRAINT interview_sessions_status_check CHECK (status IN ('ACTIVE', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT interview_sessions_turns_check CHECK (max_turns BETWEEN 1 AND 20
        AND turns_answered BETWEEN 0 AND max_turns),
    CONSTRAINT interview_sessions_credits_check CHECK (credits_consumed >= 0),
    CONSTRAINT interview_sessions_completed_check CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);

-- The user's history, newest first.
CREATE INDEX interview_sessions_user_created_idx ON interview_sessions (user_id, created_at DESC);

CREATE TABLE interview_turns (
    id              UUID         PRIMARY KEY,
    session_id      UUID         NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE,
    position        SMALLINT     NOT NULL,
    role            VARCHAR(12)  NOT NULL,
    content         TEXT         NOT NULL,
    category        VARCHAR(20),
    question_source VARCHAR(10),
    feedback        JSONB,
    idempotency_key VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT interview_turns_position_key UNIQUE (session_id, position),
    CONSTRAINT interview_turns_position_check CHECK (position >= 0),
    CONSTRAINT interview_turns_role_check CHECK (role IN ('INTERVIEWER', 'CANDIDATE')),
    CONSTRAINT interview_turns_content_check CHECK (char_length(content) BETWEEN 1 AND 20000),
    CONSTRAINT interview_turns_category_check CHECK (category IS NULL
        OR category IN ('behavioral', 'technical', 'role_specific')),
    CONSTRAINT interview_turns_source_check CHECK (question_source IS NULL OR question_source IN ('PREP', 'GENERATED')),
    CONSTRAINT interview_turns_shape_check CHECK (
        (role = 'INTERVIEWER' AND feedback IS NULL AND idempotency_key IS NULL AND category IS NOT NULL
            AND question_source IS NOT NULL)
        OR (role = 'CANDIDATE' AND category IS NULL AND question_source IS NULL))
);

CREATE UNIQUE INDEX interview_turns_session_key_uk ON interview_turns (session_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
