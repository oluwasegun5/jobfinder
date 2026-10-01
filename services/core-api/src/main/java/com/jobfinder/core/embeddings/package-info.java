/**
 * Embeddings for jobs and resume versions: keeps {@code jobs.embedding} and {@code resume_versions.embedding}
 * current (docs/adr/0022-embeddings-pipeline.md). It listens for content changes in ingestion and profile, queues
 * the ids, and serves ai-service's internal endpoints for fetching the text to embed and storing the vector.
 */
package com.jobfinder.core.embeddings;
