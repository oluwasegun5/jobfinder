-- P4.5a application tracker (docs/adr/0032-application-tracker.md): applications, their status history and reminders.
--
-- applications: one row per job the user applied to, saved or tracks, or per application entered by hand for a job found
-- elsewhere (job_id null).
--
--   status        the columns of the Kanban board: SAVED, APPLIED, SCREENING, INTERVIEW, OFFER, REJECTED, WITHDRAWN.
--   job_id        the job it was made for, a plain UUID with no foreign key (jobs are owned by ingestion and may expire or
--                 be removed; title and company are copied so the row stands on its own). One application per user and
--                 job (partial unique index), which is what makes "I applied" safe to send twice.
--   pack_id, resume_document_id, cover_letter_document_id, screening_answers_document_id
--                 what was used for the application: the pack and the approved documents of generated_documents, plain
--                 UUIDs with no foreign keys (as application_packs has none: an approved document outlives its pack, and
--                 a deleted draft must not block anything here). The service only stores ids it found for the same user.
--   applied_at    when the user applied (set when the application leaves SAVED for a status that means "applied").
--   status_changed_at  when the status last changed; the board is ordered by it.
CREATE TABLE applications (
    id                           UUID          PRIMARY KEY,
    user_id                      UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id                       UUID,
    title                        VARCHAR(400)  NOT NULL,
    company                      VARCHAR(400),
    url                          VARCHAR(2000),
    status                       VARCHAR(20)   NOT NULL,
    notes                        TEXT,
    applied_at                   TIMESTAMPTZ,
    pack_id                      UUID,
    resume_document_id           UUID,
    cover_letter_document_id     UUID,
    screening_answers_document_id UUID,
    status_changed_at            TIMESTAMPTZ   NOT NULL,
    created_at                   TIMESTAMPTZ   NOT NULL,
    updated_at                   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT applications_status_check CHECK (status IN
        ('SAVED', 'APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT applications_notes_check CHECK (notes IS NULL OR char_length(notes) <= 10000)
);

CREATE UNIQUE INDEX applications_user_job_uk ON applications (user_id, job_id) WHERE job_id IS NOT NULL;
CREATE INDEX applications_user_status_idx ON applications (user_id, status, status_changed_at DESC);

-- application_events: the status history, append only. The first event of an application has no from_status (it records
-- the status the application was created with). `seq` orders events that share a timestamp. An event can be deleted only
-- with its application (the cascade), never changed: a trigger refuses every UPDATE.
CREATE TABLE application_events (
    seq            BIGINT       GENERATED ALWAYS AS IDENTITY,
    id             UUID         PRIMARY KEY,
    application_id UUID         NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    user_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    note           VARCHAR(1000),
    at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT application_events_from_check CHECK (from_status IS NULL OR from_status IN
        ('SAVED', 'APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT application_events_to_check CHECK (to_status IN
        ('SAVED', 'APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT application_events_changed_check CHECK (from_status IS DISTINCT FROM to_status)
);

CREATE INDEX application_events_application_idx ON application_events (application_id, seq);

CREATE FUNCTION application_events_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'application_events is append only' USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER application_events_no_update BEFORE UPDATE ON application_events
    FOR EACH ROW EXECUTE FUNCTION application_events_append_only();

-- reminders: an email to send the user at due_at about one application.
--
--   kind           FOLLOW_UP, INTERVIEW or CUSTOM (the user's own note).
--   state          PENDING until sent or cancelled. SENT sets sent_at. CANCELLED sets cancel_reason: USER (deleted),
--                  APPLICATION_CLOSED (the application became REJECTED or WITHDRAWN), EMAIL_DISABLED or NO_RECIPIENT
--                  (the user cannot receive optional mail), SEND_FAILED (out of attempts).
--   attempts, claimed_at  the sender claims a reminder in one UPDATE ... FOR UPDATE SKIP LOCKED before it sends, so two
--                  instances never send the same one; a claim whose sender died is taken over after a delay, and that
--                  delay is also the wait before a failed send is tried again.
CREATE TABLE reminders (
    id             UUID         PRIMARY KEY,
    application_id UUID         NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    user_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind           VARCHAR(20)  NOT NULL,
    note           VARCHAR(500),
    due_at         TIMESTAMPTZ  NOT NULL,
    state          VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    sent_at        TIMESTAMPTZ,
    cancel_reason  VARCHAR(30),
    attempts       INTEGER      NOT NULL DEFAULT 0,
    claimed_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT reminders_kind_check CHECK (kind IN ('FOLLOW_UP', 'INTERVIEW', 'CUSTOM')),
    CONSTRAINT reminders_state_check CHECK (state IN ('PENDING', 'SENT', 'CANCELLED')),
    CONSTRAINT reminders_sent_check CHECK ((state = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT reminders_cancel_check CHECK ((state = 'CANCELLED') = (cancel_reason IS NOT NULL))
);

CREATE INDEX reminders_due_idx ON reminders (due_at) WHERE state = 'PENDING';
CREATE INDEX reminders_application_idx ON reminders (application_id, due_at);
CREATE INDEX reminders_user_idx ON reminders (user_id);
