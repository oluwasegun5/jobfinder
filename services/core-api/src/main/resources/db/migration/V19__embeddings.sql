-- Embeddings (PLAN.md section 5, P2.5; docs/adr/0022-embeddings-pipeline.md).
--
-- A job or a resume version carries one embedding, plus what is needed to tell whether it is still current:
--   embedding_model       the pinned provider model that produced it (app.embeddings.model)
--   embedding_input_hash  sha256 of the exact text that was embedded (text-template version included)
--   embedded_at           when it was written
-- A row is stale when its hash differs from the hash of the text built from the row today, or its model differs
-- from the pinned one; the pipeline and the backfill re-embed exactly those rows.
--
-- The dimension is part of the column type: vector(1024) is voyage-4 at its default output dimension.
-- Changing app.embeddings.dimension needs a NEW migration that drops the embeddings and retypes the column
-- (the application checks the column against the configuration at startup and refuses to start on a mismatch).
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE jobs
    ADD COLUMN embedding            vector(1024),
    ADD COLUMN embedding_model      VARCHAR(100),
    ADD COLUMN embedding_input_hash CHAR(64),
    ADD COLUMN embedded_at          TIMESTAMPTZ,
    ADD CONSTRAINT jobs_embedding_complete_check CHECK (
        (embedding IS NULL AND embedding_model IS NULL AND embedding_input_hash IS NULL AND embedded_at IS NULL)
        OR (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_input_hash IS NOT NULL
            AND embedded_at IS NOT NULL));

ALTER TABLE resume_versions
    ADD COLUMN embedding            vector(1024),
    ADD COLUMN embedding_model      VARCHAR(100),
    ADD COLUMN embedding_input_hash CHAR(64),
    ADD COLUMN embedded_at          TIMESTAMPTZ,
    ADD CONSTRAINT resume_versions_embedding_complete_check CHECK (
        (embedding IS NULL AND embedding_model IS NULL AND embedding_input_hash IS NULL AND embedded_at IS NULL)
        OR (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_input_hash IS NOT NULL
            AND embedded_at IS NOT NULL));

-- Approximate nearest-neighbour search by cosine distance (the recall step of PLAN.md section 7).
-- HNSW needs no training data, so the indexes can exist before the first vector does.
CREATE INDEX jobs_embedding_hnsw_idx ON jobs USING hnsw (embedding vector_cosine_ops);
CREATE INDEX resume_versions_embedding_hnsw_idx ON resume_versions USING hnsw (embedding vector_cosine_ops);
