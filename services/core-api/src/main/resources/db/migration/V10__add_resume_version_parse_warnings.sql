-- Grounding warnings from CV parsing (P1.5), kept so the review screen (P1.6) can flag employers,
-- schools and projects the parser could not find in the CV text. Backward compatible: nullable,
-- unused by older code. Shape: [{"path": "experience[0].company", "code": "not_in_source"}, ...].
-- Only the upload version carries warnings; a version the user edited has none.
ALTER TABLE resume_versions
    ADD COLUMN parse_warnings JSONB;
