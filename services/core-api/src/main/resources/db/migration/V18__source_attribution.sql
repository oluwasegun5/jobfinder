-- P2.4: the credit an aggregator's terms require wherever its jobs are shown (ADR 0021). It is a property of
-- the source, not of a job, so it lives on sources; the adapter owns the wording and the registrar rewrites it
-- on every startup, so a change in a source's terms is a code change, not a data fix. All columns are null
-- for a source that asks for no credit (the ATS boards). Each listing's own link is job_sources.url.
ALTER TABLE sources
    ADD COLUMN attribution_name  VARCHAR(100),
    ADD COLUMN attribution_text  VARCHAR(200),
    ADD COLUMN attribution_url   VARCHAR(500),
    ADD COLUMN attribution_notes VARCHAR(1500);
