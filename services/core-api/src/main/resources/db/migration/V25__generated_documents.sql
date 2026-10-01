-- P4.1 resume tailoring (PLAN.md section 5 documents; docs/adr/0029-resume-tailoring.md).
--
-- generated_documents: AI-generated documents a user reviews and approves. Only TAILORED_RESUME exists today (cover
-- letters arrive with P4.3 by widening the type CHECK in a new migration).
--
--   status          GENERATING (placeholder while ai-service works), DRAFT, FACT_CHECK_FAILED (a BLOCKING fact-check
--                   flag is present; cannot be approved), APPROVED (final and immutable).
--   job_id, base_resume_version_id
--                   provenance only, deliberately WITHOUT foreign keys: an approved document must survive the job
--                   being deleted and the resume version being replaced or deleted. job_title and job_company are
--                   copied so the document still says what it was written for.
--   source_content  snapshot of the resume (structured JSON) the draft was made from: what every fact is checked
--                   against and what a rejected change reverts to. It does not move when the user edits their resume.
--   content         the current document: source_content with the accepted changes applied (null while GENERATING).
--   changes         the reviewable units of difference (id, section, op, path, before, after, rationale, state
--                   ACCEPTED/REJECTED, edited).
--   fact_check      the last fact-check result for `content` (passed, blocking, warnings, flags).
--   version         optimistic-lock counter; the API refuses an edit made against an older version.
--
-- At most one open (GENERATING, DRAFT or FACT_CHECK_FAILED) draft exists per user, job, resume version and type: the
-- unique index is what makes a double click, or two tabs, create one draft and one model call.
CREATE TABLE generated_documents (
    id                     UUID         PRIMARY KEY,
    user_id                UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type                   VARCHAR(30)  NOT NULL,
    status                 VARCHAR(30)  NOT NULL,
    job_id                 UUID         NOT NULL,
    job_title              VARCHAR(400) NOT NULL,
    job_company            VARCHAR(400),
    base_resume_version_id UUID         NOT NULL,
    prompt_version         VARCHAR(100) NOT NULL,
    model                  VARCHAR(100),
    source_content         JSONB        NOT NULL,
    content                JSONB,
    changes                JSONB        NOT NULL DEFAULT '[]'::jsonb,
    fact_check             JSONB,
    version                INTEGER      NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    approved_at            TIMESTAMPTZ,
    CONSTRAINT generated_documents_type_check CHECK (type IN ('TAILORED_RESUME')),
    CONSTRAINT generated_documents_status_check
        CHECK (status IN ('GENERATING', 'DRAFT', 'FACT_CHECK_FAILED', 'APPROVED')),
    CONSTRAINT generated_documents_content_check CHECK (status = 'GENERATING' OR content IS NOT NULL),
    CONSTRAINT generated_documents_approved_at_check CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL)),
    -- A document with a BLOCKING fact-check flag can never be APPROVED, whatever wrote the row.
    CONSTRAINT generated_documents_approved_clean_check CHECK (
        status <> 'APPROVED'
        OR (fact_check IS NOT NULL AND (fact_check ->> 'blocking')::integer = 0 AND (fact_check ->> 'passed')::boolean)
    )
);

CREATE UNIQUE INDEX generated_documents_open_uk
    ON generated_documents (user_id, job_id, base_resume_version_id, type)
    WHERE status IN ('GENERATING', 'DRAFT', 'FACT_CHECK_FAILED');
CREATE INDEX generated_documents_user_created_idx ON generated_documents (user_id, created_at DESC);
CREATE INDEX generated_documents_user_job_idx ON generated_documents (user_id, job_id);

-- APPROVED documents are immutable, enforced here and not only in the API. An UPDATE of an approved row is always
-- refused. A DELETE is refused too, except inside a transaction that set app.purge_documents = 'on': account
-- deletion (the documents module's UserDeletionRequested handler) is the only code that does, because erasing a
-- person's data must still be possible. A DELETE of a draft is always allowed. SQLSTATE 23000 maps to Spring's
-- DataIntegrityViolationException.
CREATE FUNCTION generated_documents_protect_approved() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'APPROVED' THEN
        IF TG_OP = 'DELETE' AND coalesce(current_setting('app.purge_documents', true), '') = 'on' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'approved documents are immutable (% refused)', TG_OP USING ERRCODE = '23000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER generated_documents_no_change_when_approved
    BEFORE UPDATE OR DELETE ON generated_documents
    FOR EACH ROW EXECUTE FUNCTION generated_documents_protect_approved();
