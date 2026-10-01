-- Normalized job postings (PLAN.md section 5). Nothing writes here until the normalizer (P2.2).
-- embedding (pgvector) and search (tsvector) come with their own migrations (P2.5, P2.6), and the
-- value sets of work_mode, employment_type and seniority get CHECK constraints when the normalizer
-- fixes them, so they are plain strings for now.
CREATE TABLE jobs (
    id               UUID PRIMARY KEY,
    company_id       UUID         NOT NULL REFERENCES companies (id),
    title            VARCHAR(500) NOT NULL,
    normalized_title VARCHAR(500) NOT NULL,
    description_html TEXT,
    description_text TEXT,
    location_raw     VARCHAR(500),
    city             VARCHAR(200),
    country          VARCHAR(100),
    work_mode        VARCHAR(30),
    employment_type  VARCHAR(30),
    seniority        VARCHAR(30),
    salary_min       NUMERIC(14, 2),
    salary_max       NUMERIC(14, 2),
    salary_currency  VARCHAR(3),
    salary_period    VARCHAR(20),
    apply_url        VARCHAR(2000),
    apply_channel    VARCHAR(20)  NOT NULL DEFAULT 'EXTERNAL',
    posted_at        TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    fingerprint      VARCHAR(64)  NOT NULL,
    skills           TEXT[]       NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT jobs_fingerprint_uk UNIQUE (fingerprint),
    CONSTRAINT jobs_status_check CHECK (status IN ('ACTIVE', 'EXPIRED')),
    CONSTRAINT jobs_apply_channel_check CHECK (apply_channel IN ('EXTERNAL', 'ATS_API', 'EMAIL')),
    CONSTRAINT jobs_salary_range_check CHECK (salary_min IS NULL OR salary_max IS NULL OR salary_min <= salary_max)
);

CREATE INDEX jobs_status_posted_at_idx ON jobs (status, posted_at DESC);
CREATE INDEX jobs_country_work_mode_idx ON jobs (country, work_mode);
CREATE INDEX jobs_company_id_idx ON jobs (company_id);

-- A job can be listed by several sources; each listing keeps its own external id and URL.
CREATE TABLE job_sources (
    id          UUID PRIMARY KEY,
    job_id      UUID         NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    source_id   UUID         NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    external_id VARCHAR(255) NOT NULL,
    url         VARCHAR(2000),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT job_sources_external_uk UNIQUE (source_id, external_id)
);

CREATE INDEX job_sources_job_id_idx ON job_sources (job_id);
