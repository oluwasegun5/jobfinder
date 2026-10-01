-- P3.4 digests and alerts (docs/adr/0028-notifications.md).
--
-- saved_searches: a search the user keeps, in the shape of the GET /jobs filters (keyword, work mode, employment type,
-- seniority, countries, city, salary floor and its currency), and how often to hear about new jobs for it. last_run_at
-- is the watermark: jobs first seen after it (jobs.created_at) are "new" for this search. It starts at creation time,
-- so saving a search never mails the existing backlog, and it only moves forward when a run finished (sent or had
-- nothing to send).
CREATE TABLE saved_searches (
    id               UUID PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             VARCHAR(100)  NOT NULL,
    q                VARCHAR(200),
    work_modes       TEXT[]        NOT NULL DEFAULT '{}',
    employment_types TEXT[]        NOT NULL DEFAULT '{}',
    seniorities      TEXT[]        NOT NULL DEFAULT '{}',
    countries        TEXT[]        NOT NULL DEFAULT '{}',
    city             VARCHAR(200),
    min_salary       NUMERIC(14, 2),
    salary_currency  CHAR(3),
    frequency        VARCHAR(10)   NOT NULL DEFAULT 'DAILY',
    last_run_at      TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT saved_searches_frequency_check CHECK (frequency IN ('INSTANT', 'DAILY', 'WEEKLY', 'OFF')),
    CONSTRAINT saved_searches_salary_check CHECK (min_salary IS NULL OR (min_salary > 0 AND salary_currency IS NOT NULL))
);

CREATE INDEX saved_searches_user_idx ON saved_searches (user_id, created_at);
CREATE INDEX saved_searches_active_idx ON saved_searches (frequency) WHERE frequency <> 'OFF';

-- notification_preferences: one row per user, created the first time they save settings or unsubscribe. No row means
-- the defaults below (digests and instant alerts are opt-in). digests_unsubscribed_at and marketing_unsubscribed_at
-- are the one-click unsubscribe scopes (an email link, no login): they stop every digest, or every digest and alert,
-- until the user turns something on again in their settings. Transactional mail (verification, password reset) does
-- not read this table.
CREATE TABLE notification_preferences (
    user_id                   UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email_enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    digest_enabled            BOOLEAN     NOT NULL DEFAULT FALSE,
    digest_frequency          VARCHAR(10) NOT NULL DEFAULT 'DAILY',
    digest_hour               SMALLINT    NOT NULL DEFAULT 8,
    digest_weekday            SMALLINT    NOT NULL DEFAULT 1,
    timezone                  VARCHAR(64),
    instant_enabled           BOOLEAN     NOT NULL DEFAULT FALSE,
    instant_threshold         SMALLINT    NOT NULL DEFAULT 85,
    digests_unsubscribed_at   TIMESTAMPTZ,
    marketing_unsubscribed_at TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_preferences_frequency_check CHECK (digest_frequency IN ('DAILY', 'WEEKLY')),
    CONSTRAINT notification_preferences_hour_check CHECK (digest_hour BETWEEN 0 AND 23),
    CONSTRAINT notification_preferences_weekday_check CHECK (digest_weekday BETWEEN 1 AND 7),
    CONSTRAINT notification_preferences_threshold_check CHECK (instant_threshold BETWEEN 50 AND 100)
);

-- notification_log: the outbox and the audit trail. A row is claimed (inserted PENDING) before anything is sent, and
-- the unique key (user, kind, scope, window) makes the claim the idempotency guard: a digest for a window (a local
-- day, a local week) or an alert for a set of jobs is claimed once, so a re-run, a second instance or a retry loop
-- cannot send it twice. scope is the saved search id for search emails and '' otherwise. job_ids is what the email
-- listed: it is both the audit record and what "never repeat a job" reads. An email that failed is retried (attempts,
-- next_attempt_at) a bounded number of times while its window is open. last_error holds an exception class name only:
-- never an address, a token or a message from the mail server.
CREATE TABLE notification_log (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind            VARCHAR(30)  NOT NULL,
    scope           VARCHAR(40)  NOT NULL DEFAULT '',
    saved_search_id UUID         REFERENCES saved_searches (id) ON DELETE SET NULL,
    window_key      VARCHAR(80)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    attempts        SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(200),
    job_ids         UUID[]       NOT NULL DEFAULT '{}',
    item_count      INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT notification_log_kind_check CHECK (kind IN
        ('FOR_YOU_DIGEST', 'SAVED_SEARCH_DIGEST', 'INSTANT_MATCH_ALERT', 'INSTANT_SEARCH_ALERT')),
    CONSTRAINT notification_log_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT notification_log_window_uk UNIQUE (user_id, kind, scope, window_key)
);

CREATE INDEX notification_log_user_sent_idx ON notification_log (user_id, sent_at DESC) WHERE status = 'SENT';
CREATE INDEX notification_log_created_idx ON notification_log (created_at);
