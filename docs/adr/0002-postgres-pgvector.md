# 0002. PostgreSQL with pgvector for data and vector search

## Status
Accepted

## Context
The product needs relational storage (users, profiles, jobs, applications, ledger) and vector
similarity search (job and resume embeddings for semantic search and first-stage matching).
Running a separate vector database or search cluster adds another system to operate, back up,
secure and keep consistent with the primary store.

## Decision
Use PostgreSQL 16 with the pgvector extension as the single datastore for both relational data
and embeddings. Vector columns live next to the rows they describe and use HNSW indexes.
Locally and in tests the `pgvector/pgvector:pg16` image is used (compose and Testcontainers).
Schema changes go through Flyway migrations only.

## Consequences
- One database to run, back up (daily snapshots + PITR) and secure, and account deletion can
  purge vectors in the same transaction as the rows they belong to.
- Filters and vector search combine in one SQL query.
- pgvector will not match a dedicated engine at very large scale. We move to OpenSearch or a
  dedicated vector store only when search latency or recall metrics say so.
- Managed Postgres providers must support pgvector; this constrains hosting choices.
