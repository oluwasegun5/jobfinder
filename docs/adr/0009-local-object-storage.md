# 0009. Use Adobe S3Mock instead of MinIO for local object storage

## Status
Accepted

## Context
`PLAN.md` §4 and `PROMPTS.md` P0.1 specify MinIO as the S3-compatible object
store in the local `docker-compose` stack (production uses Cloudflare R2 per
§12, so the local choice is not architecturally significant).

While building the compose stack, both `minio/minio` and `minio/mc` failed to
pull from Docker Hub with "pull access denied ... repository does not exist or
may require 'docker login'", and `quay.io/minio/mc` returned 401. MinIO Inc.
restricted these images to require a paid subscription, so they are no longer
usable as free public images for a local dev stack.

## Decision
Use `adobe/s3mock` (MIT-licensed, purpose-built for local S3-API testing) as
the local object store, exposed on port 9000 (mapped from its internal 9090
HTTP port). Bucket auto-creation is done by a one-shot `amazon/aws-cli`
container that runs `aws s3 mb` against it on startup, replacing the `mc`
init step. Env vars are renamed from `MINIO_*` to `OBJECT_STORAGE_*` to avoid
implying a MinIO-specific API.

This is a drop-in swap: both speak the S3 API, so `services/profile`'s
storage client (added in P1.4) targets it the same way it would target MinIO
or R2 — via an S3-compatible endpoint URL, access key, and secret, all from
config.

## Consequences
- No MinIO web console locally; S3Mock has no admin UI. Bucket contents can be
  inspected with `aws --endpoint-url http://localhost:9000 s3 ls s3://jobfinder`.
- S3Mock keeps objects in memory by default (no named volume) — local data
  does not survive `docker compose down`, which is acceptable for a dev/test
  store.
- If MinIO images become freely available again, or a self-hosted MinIO
  license is acquired, this can be reverted by swapping the `object-storage`
  service back to `minio/minio` + `minio/mc` without touching application code.
