-- An uploaded CV. The file itself lives in object storage under file_key (private bucket); the
-- key is generated server-side and never derived from the uploaded file name.
CREATE TABLE resumes (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label        VARCHAR(120) NOT NULL,
    file_key     VARCHAR(255) NOT NULL UNIQUE,
    file_type    VARCHAR(10)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    is_primary   BOOLEAN      NOT NULL DEFAULT FALSE,
    parse_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT resumes_file_type_check CHECK (file_type IN ('PDF', 'DOCX')),
    CONSTRAINT resumes_parse_status_check CHECK (parse_status IN ('PENDING', 'PARSED', 'FAILED'))
);

CREATE INDEX resumes_user_id_idx ON resumes (user_id, created_at DESC);
-- At most one primary resume per user.
CREATE UNIQUE INDEX resumes_one_primary_per_user_idx ON resumes (user_id) WHERE is_primary;
