-- Results of CV parsing (P1.5). Backward compatible: columns are nullable and unused by older code.
--
-- resumes.parse_error: stable reason code when parse_status = 'FAILED' (e.g. no_extractable_text,
--   parser_unavailable); NULL otherwise.
-- resume_versions.model / prompt_version: which model and prompt produced `structured`, kept with
--   every stored AI output (PLAN.md section 7); NULL for human-authored versions.
ALTER TABLE resumes
    ADD COLUMN parse_error VARCHAR(50);

ALTER TABLE resume_versions
    ADD COLUMN model          VARCHAR(100),
    ADD COLUMN prompt_version VARCHAR(100);
