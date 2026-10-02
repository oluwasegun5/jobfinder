-- P4.3 cover letters, screening answers and the application pack (docs/adr/0031-cover-letters-and-application-pack.md).
--
-- generated_documents learns two more types and one more status, and keeps every guarantee of V25:
--
--   type     COVER_LETTER, SCREENING_ANSWERS next to TAILORED_RESUME. `content` is the letter (sender, recipient,
--            salutation, paragraphs, closing, signature) or the answers (id, question, answer, status, hint); `changes`
--            stays empty for them (no diff: the text is the document) and `source_content` is still the resume snapshot
--            the prose is fact-checked against.
--   status   SUPERSEDED: a draft that was replaced by a regeneration with other options. It is kept (history), read-only
--            in the API and never approvable. An APPROVED document is never superseded and never overwritten: a
--            regeneration of it makes a new draft beside it.
--   options  tone, length and the user's notes of a letter or an answer set, plus the text the fact check may rely on
--            (the job's title and company, the notes); null for tailored resumes.
--
-- The V25 trigger (an APPROVED row can neither be updated nor deleted, except by account deletion) and the
-- approved_clean CHECK (no APPROVED row with a blocking flag) are not touched: they apply to every type. One more CHECK
-- is added: an APPROVED set of screening answers has no question left in NEEDS_INPUT.
--
-- The single "open draft" index of V25 is split in two so that a regeneration can have its placeholder (GENERATING)
-- while the draft it replaces still exists: one GENERATING row, and one DRAFT or FACT_CHECK_FAILED row, per user, job,
-- resume version and type. A double click still makes one placeholder, so one model call and one allowance.
ALTER TABLE generated_documents DROP CONSTRAINT generated_documents_type_check;
ALTER TABLE generated_documents ADD CONSTRAINT generated_documents_type_check
    CHECK (type IN ('TAILORED_RESUME', 'COVER_LETTER', 'SCREENING_ANSWERS'));

ALTER TABLE generated_documents DROP CONSTRAINT generated_documents_status_check;
ALTER TABLE generated_documents ADD CONSTRAINT generated_documents_status_check
    CHECK (status IN ('GENERATING', 'DRAFT', 'FACT_CHECK_FAILED', 'APPROVED', 'SUPERSEDED'));

ALTER TABLE generated_documents ADD COLUMN options JSONB;

ALTER TABLE generated_documents ADD CONSTRAINT generated_documents_answered_check CHECK (
    status <> 'APPROVED'
    OR type <> 'SCREENING_ANSWERS'
    OR position('"status": "NEEDS_INPUT"' IN content::text) = 0
);

DROP INDEX generated_documents_open_uk;
CREATE UNIQUE INDEX generated_documents_generating_uk
    ON generated_documents (user_id, job_id, base_resume_version_id, type)
    WHERE status = 'GENERATING';
CREATE UNIQUE INDEX generated_documents_draft_uk
    ON generated_documents (user_id, job_id, base_resume_version_id, type)
    WHERE status IN ('DRAFT', 'FACT_CHECK_FAILED');

-- application_packs: the tailored CV, the cover letter and the screening answers of one job, as one unit.
--
--   status   GENERATING (parts are being made), COMPLETE (every requested part is READY), PARTIAL (some are, some
--            failed or hit the daily cap), FAILED (none is).
--   options  what was asked for: tone, length, notes and the parts to include.
--   parts    one entry per requested part, keyed by document type: its state (PENDING, READY, FAILED,
--            BLOCKED_BY_CAP), the generated_documents id when READY, and a typed error otherwise (code, message,
--            retryable, resetsAt). The documents themselves live in generated_documents and are reviewed, edited and
--            approved one by one; the pack only points at them (no foreign keys, as for generated_documents: an
--            approved document must outlive its pack, and a deleted draft shows as MISSING instead of breaking it).
--
-- One pack per user, job and resume version: a second request returns it, which is what makes a double click safe.
CREATE TABLE application_packs (
    id                     UUID         PRIMARY KEY,
    user_id                UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id                 UUID         NOT NULL,
    job_title              VARCHAR(400) NOT NULL,
    job_company            VARCHAR(400),
    base_resume_version_id UUID         NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    options                JSONB        NOT NULL,
    parts                  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version                INTEGER      NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT application_packs_status_check CHECK (status IN ('GENERATING', 'COMPLETE', 'PARTIAL', 'FAILED'))
);

CREATE UNIQUE INDEX application_packs_job_uk ON application_packs (user_id, job_id, base_resume_version_id);
CREATE INDEX application_packs_user_created_idx ON application_packs (user_id, created_at DESC);
