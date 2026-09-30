-- Structured content of a resume. Version 1 is created on upload with structured = NULL and is
-- filled in once the CV has been parsed; edits and tailored copies add later versions.
CREATE TABLE resume_versions (
    id             UUID PRIMARY KEY,
    resume_id      UUID        NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    version_number INTEGER     NOT NULL,
    structured     JSONB,
    source         VARCHAR(20) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT resume_versions_source_check CHECK (source IN ('UPLOAD', 'EDIT', 'TAILORED')),
    CONSTRAINT resume_versions_number_uk UNIQUE (resume_id, version_number)
);
