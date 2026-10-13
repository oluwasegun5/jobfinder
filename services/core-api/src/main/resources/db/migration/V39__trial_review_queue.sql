-- Trial review queue (docs/adr/0047-trial-review-queue.md): what the gate records so an admin can review HOLD and DENIED
-- accounts, and the append-only audit of every admin decision. Backward compatible: new columns have defaults and the
-- gate's existing statements do not touch them.

-- 1. What the gate decided and why, per account. decision_reasons: [{"code": "cv-phone", "points": 30, "hard": false}],
--    codes and points only (never a digest or a matched account). milestones_reached: the milestones (bit set as in
--    milestones) the account reached, granted or not; an approval releases the reached tranches that are not yet granted.
--    review_decision / reviewed_at: set once an admin has approved or denied the account; DENIED is final.
ALTER TABLE trial_accounts
    ADD COLUMN milestones_reached INTEGER     NOT NULL DEFAULT 0 CHECK (milestones_reached BETWEEN 0 AND 15),
    ADD COLUMN decision_reasons   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN review_decision    VARCHAR(8)  NULL CHECK (review_decision IN ('APPROVED', 'DENIED')),
    ADD COLUMN reviewed_at        TIMESTAMPTZ NULL,
    ADD CONSTRAINT trial_accounts_review_pair_check CHECK ((review_decision IS NULL) = (reviewed_at IS NULL));

-- Existing rows: what was granted was reached. A held or denied account certainly reached M1 (the row is created there).
-- Later milestones a denied account reached were not recorded before this migration and cannot be recovered.
UPDATE trial_accounts
   SET milestones_reached = milestones | CASE WHEN state IN ('HOLD', 'DENIED') THEN 1 ELSE 0 END;

-- The queue: pending HOLD and DENIED accounts, oldest first.
CREATE INDEX trial_accounts_review_queue_idx ON trial_accounts (created_at, user_id)
    WHERE state IN ('HOLD', 'DENIED') AND review_decision IS NULL;

-- 2. Audit of admin decisions. Append-only. user_id and admin_id carry no foreign key on purpose: deleting either account
--    must neither rewrite nor block the trail. note: the admin's reason, 1 to 1000 characters, no personal data by
--    instruction. Kept 24 months (730 days), then purged by the retention task.
CREATE TABLE trial_review_decisions (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL,
    admin_id        UUID          NOT NULL,
    action          VARCHAR(7)    NOT NULL CHECK (action IN ('APPROVE', 'DENY')),
    state_before    VARCHAR(10)   NOT NULL CHECK (state_before IN ('HOLD', 'DENIED')),
    note            TEXT          NOT NULL CHECK (char_length(btrim(note)) BETWEEN 1 AND 1000),
    credits_granted NUMERIC(18, 6) NOT NULL DEFAULT 0 CHECK (credits_granted >= 0),
    decided_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX trial_review_decisions_user_idx    ON trial_review_decisions (user_id, decided_at);
CREATE INDEX trial_review_decisions_decided_idx ON trial_review_decisions (decided_at);

-- Append-only: no UPDATE, no TRUNCATE, and DELETE only for rows past the 730-day retention.
CREATE FUNCTION trial_review_decisions_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.decided_at < now() - interval '730 days' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'trial_review_decisions is append-only (% refused)', TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trial_review_decisions_no_update_delete
    BEFORE UPDATE OR DELETE ON trial_review_decisions
    FOR EACH ROW EXECUTE FUNCTION trial_review_decisions_guard();

CREATE TRIGGER trial_review_decisions_no_truncate
    BEFORE TRUNCATE ON trial_review_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION trial_review_decisions_guard();
