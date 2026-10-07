-- P6.4 compliance (docs/adr/0040-compliance-and-account-data.md).
-- Consent for AI processing of the user's data (CV text, profile, job matches) by the model providers. Recorded on the
-- user row: the version of the consent text the user accepted and when. NULL means no consent: no AI call is made for
-- the user, whether they start it or a background job would (billing's gate refuses it). Existing accounts start with
-- NULL on purpose: consent that was never asked for cannot be presumed. Withdrawal sets both columns back to NULL.
ALTER TABLE users ADD COLUMN ai_consent_version VARCHAR(20);
ALTER TABLE users ADD COLUMN ai_consent_at TIMESTAMPTZ;
