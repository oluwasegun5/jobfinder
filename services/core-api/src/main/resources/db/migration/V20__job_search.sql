-- P2.6: what job search needs on top of the P2.1 to P2.5 tables (docs/adr/0023-job-search.md).
--
-- 1. jobs.search, the weighted full-text document every keyword is matched against: title (A) > company name (B) >
--    skills (C) > description (D); and jobs.search_head, the same without the description. Jobs that match in the
--    head are ranked (ts_rank_cd) on it; jobs that match only in the description are listed after them, newest
--    first, without being ranked at all. (ts_rank_cd has to read every matching document, and a description's
--    tsvector is large, compressed and stored out of line: ranking on it made a keyword that matches most jobs
--    cost seconds. The head is a few dozen lexemes, and the description-only tier needs no rank.) A generated column cannot read companies, so a trigger
--    keeps both current: on insert, and on an update that really changes the title, company, skills or description
--    (an update that only writes an embedding, or a refresh that rewrites identical text, keeps the stored vectors
--    instead of re-parsing a long description).
-- 2. jobs.sort_at and jobs.salary_annual_top, generated: the keyset sort key and the filterable salary.
-- 3. The indexes the search filters and sorts use.
-- 4. user_job_actions (PLAN.md section 5): per-user SAVED / HIDDEN state, the feedback signal ranking will learn from.

CREATE FUNCTION jobs_search_head_vector(p_title TEXT, p_company_id UUID, p_skills TEXT[])
    RETURNS tsvector
    LANGUAGE sql
    STABLE
AS
$$
SELECT setweight(to_tsvector('english', coalesce(p_title, '')), 'A')
       || setweight(to_tsvector('english', coalesce((SELECT c.name FROM companies c WHERE c.id = p_company_id), '')), 'B')
       || setweight(to_tsvector('english', coalesce(array_to_string(p_skills, ' '), '')), 'C')
$$;

CREATE FUNCTION jobs_search_vector(p_title TEXT, p_company_id UUID, p_skills TEXT[], p_description TEXT)
    RETURNS tsvector
    LANGUAGE sql
    STABLE
AS
$$
SELECT jobs_search_head_vector(p_title, p_company_id, p_skills)
       || setweight(to_tsvector('english', coalesce(left(p_description, 100000), '')), 'D')
$$;

CREATE FUNCTION jobs_refresh_search() RETURNS trigger
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.search IS NOT NULL
        AND NEW.title IS NOT DISTINCT FROM OLD.title
        AND NEW.company_id IS NOT DISTINCT FROM OLD.company_id
        AND NEW.skills IS NOT DISTINCT FROM OLD.skills
        AND NEW.description_text IS NOT DISTINCT FROM OLD.description_text THEN
        NEW.search := OLD.search;
        NEW.search_head := OLD.search_head;
        RETURN NEW;
    END IF;
    NEW.search_head := jobs_search_head_vector(NEW.title, NEW.company_id, NEW.skills);
    NEW.search := NEW.search_head || setweight(to_tsvector('english', coalesce(left(NEW.description_text, 100000), '')), 'D');
    RETURN NEW;
END
$$;

ALTER TABLE jobs
    ADD COLUMN search      tsvector,
    ADD COLUMN search_head tsvector;

CREATE TRIGGER jobs_search_refresh
    BEFORE INSERT OR UPDATE OF title, company_id, skills, description_text
    ON jobs
    FOR EACH ROW
EXECUTE FUNCTION jobs_refresh_search();

-- A renamed company changes the B part of its jobs' documents.
CREATE FUNCTION companies_refresh_job_search() RETURNS trigger
    LANGUAGE plpgsql
AS
$$
BEGIN
    UPDATE jobs
    SET search_head = jobs_search_head_vector(title, company_id, skills),
        search      = jobs_search_vector(title, company_id, skills, description_text)
    WHERE company_id = NEW.id;
    RETURN NEW;
END
$$;

CREATE TRIGGER companies_search_refresh
    AFTER UPDATE OF name
    ON companies
    FOR EACH ROW
    WHEN (OLD.name IS DISTINCT FROM NEW.name)
EXECUTE FUNCTION companies_refresh_job_search();

-- Jobs that exist already (before the GIN index exists, so the backfill does not maintain it row by row).
UPDATE jobs
SET search_head = jobs_search_head_vector(title, company_id, skills),
    search      = jobs_search_vector(title, company_id, skills, description_text);

-- sort_at: when the job was posted, or first seen when the source gave no date, and never later than first seen
-- (a source's future-dated posting must not sit at the top of every list for weeks).
-- salary_annual_top: the upper end of the stated salary as a yearly amount in the stated currency, null when no
-- salary or no period was stated (a period is never guessed, ADR 0019). "Pays at least X" is salary_annual_top >= X.
ALTER TABLE jobs
    ADD COLUMN sort_at timestamptz GENERATED ALWAYS AS (least(coalesce(posted_at, created_at), created_at)) STORED,
    ADD COLUMN salary_annual_top numeric GENERATED ALWAYS AS (
        coalesce(salary_max, salary_min) * CASE salary_period
                                               WHEN 'HOUR' THEN 2080
                                               WHEN 'DAY' THEN 260
                                               WHEN 'WEEK' THEN 52
                                               WHEN 'MONTH' THEN 12
                                               WHEN 'YEAR' THEN 1
            END) STORED;

-- Only active jobs are searchable, so every search index is partial on that and stays as small as the live set.
CREATE INDEX jobs_search_gin_idx ON jobs USING gin (search) WHERE status = 'ACTIVE';
CREATE INDEX jobs_search_head_gin_idx ON jobs USING gin (search_head) WHERE status = 'ACTIVE';
CREATE INDEX jobs_active_sort_idx ON jobs (sort_at DESC, id DESC) WHERE status = 'ACTIVE';
CREATE INDEX jobs_active_company_sort_idx ON jobs (company_id, sort_at DESC, id DESC) WHERE status = 'ACTIVE';
CREATE INDEX jobs_active_city_idx ON jobs (lower(city)) WHERE status = 'ACTIVE';
CREATE INDEX jobs_active_salary_idx ON jobs (salary_currency, salary_annual_top)
    WHERE status = 'ACTIVE' AND salary_annual_top IS NOT NULL;

CREATE TABLE user_job_actions
(
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id     UUID        NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    action     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, job_id, action),
    CONSTRAINT user_job_actions_action_check CHECK (action IN ('VIEWED', 'SAVED', 'HIDDEN', 'APPLIED'))
);

-- "My saved jobs, newest first" (keyset on created_at, job_id); job_id alone serves the cascade from jobs.
CREATE INDEX user_job_actions_user_action_idx ON user_job_actions (user_id, action, created_at DESC, job_id DESC);
CREATE INDEX user_job_actions_job_idx ON user_job_actions (job_id);
