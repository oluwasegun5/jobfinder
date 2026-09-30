-- One row of job-search preferences per user; used to filter and rank the feed.
CREATE TABLE preferences (
    id                 UUID PRIMARY KEY,
    user_id            UUID         NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    target_titles      TEXT[]       NOT NULL DEFAULT '{}',
    locations          TEXT[]       NOT NULL DEFAULT '{}',
    work_modes         TEXT[]       NOT NULL DEFAULT '{}',
    min_salary         INTEGER,
    currency           CHAR(3),
    needs_sponsorship  BOOLEAN      NOT NULL DEFAULT FALSE,
    excluded_companies TEXT[]       NOT NULL DEFAULT '{}',
    excluded_industries TEXT[]      NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT preferences_min_salary_check CHECK (min_salary IS NULL OR min_salary >= 0)
);
