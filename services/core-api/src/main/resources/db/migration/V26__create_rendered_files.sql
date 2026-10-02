-- P4.2 document rendering (docs/adr/0030-document-rendering.md).
--
-- rendered_files: the PDF and DOCX files rendered from an approved tailored resume or from one of the user's own
-- resumes. The file lives in object storage (private bucket, key below); this table is the cache index: a request for
-- the same source content, template, format and page size finds the row and renders nothing.
--
--   source_type, source_id
--                   DOCUMENT (a generated_documents id; approved content never changes) or RESUME_VERSION (a
--                   resume_versions id). No foreign keys, for the same reason as generated_documents: a rendered file
--                   of an approved document should not depend on the row it was made from being kept in step.
--   content_sha256  SHA-256 of the exact structured JSON that was rendered. It is what makes the cache safe for a
--                   resume version, whose latest content the user can still edit in place: new content, new hash,
--                   new row, new object.
--   renderer_version
--                   bumped in code when a template changes the output, so old cached files are never served for the
--                   new look (they stay in storage until the account is deleted).
--   storage_key     renders/<user id>/<source id>/<template>-<page size>-<hash>-r<renderer version>.<ext>; nothing
--                   client-supplied goes into it.
--   file_sha256     SHA-256 of the stored file, returned to the client so it can check a download.
CREATE TABLE rendered_files (
    id               UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source_type      VARCHAR(20)  NOT NULL,
    source_id        UUID         NOT NULL,
    content_sha256   CHAR(64)     NOT NULL,
    renderer_version SMALLINT     NOT NULL,
    template         VARCHAR(20)  NOT NULL,
    format           VARCHAR(10)  NOT NULL,
    page_size        VARCHAR(10)  NOT NULL,
    storage_key      VARCHAR(300) NOT NULL UNIQUE,
    file_sha256      CHAR(64)     NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT rendered_files_source_type_check CHECK (source_type IN ('DOCUMENT', 'RESUME_VERSION')),
    CONSTRAINT rendered_files_template_check CHECK (template IN ('ATS', 'STYLED')),
    CONSTRAINT rendered_files_format_check CHECK (format IN ('PDF', 'DOCX')),
    CONSTRAINT rendered_files_page_size_check CHECK (page_size IN ('A4', 'LETTER'))
);

-- One cached file per source content and look; a concurrent second render of the same thing inserts nothing.
CREATE UNIQUE INDEX rendered_files_cache_uk
    ON rendered_files (source_type, source_id, content_sha256, renderer_version, template, format, page_size);
CREATE INDEX rendered_files_user_idx ON rendered_files (user_id);
