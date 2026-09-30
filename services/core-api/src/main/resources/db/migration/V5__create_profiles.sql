-- One profile per user; the editable, structured view of the candidate (filled from the parsed CV
-- and then corrected by hand).
CREATE TABLE profiles (
    id               UUID PRIMARY KEY,
    user_id          UUID         NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    full_name        VARCHAR(200),
    headline         VARCHAR(300),
    location         VARCHAR(200),
    phone            VARCHAR(50),
    links            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    years_experience INTEGER,
    seniority        VARCHAR(30),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT profiles_years_experience_check CHECK (years_experience IS NULL OR years_experience >= 0)
);
