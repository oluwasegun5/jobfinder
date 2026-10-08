# 0041. Production deployment: one host, Docker Compose, Caddy, daily backups, CI/CD with an approval gate

## Status
Accepted

## Context
`PLAN.md` section 12 offers, for "Staging/Prod v1", either managed services on Fly.io, Railway or Render, or "a single VM
with Docker Compose + Caddy", with the web app on Vercel. `PROMPTS.md` P6.5 asks for production compose or config "for
chosen host", managed Postgres, Redis and R2, Vercel for the web, GitHub Actions deploys (staging on `main`, production on a
tag), backups with a restore runbook and a k6 load test. No host, domain, registry or provider account has been chosen, and
this task must not touch any: everything here is built and rehearsed locally against a throw-away compose project, and what
needs the owner's accounts is a named variable and a line in `docs/launch-checklist.md`. PLAN and PROMPTS do not conflict:
the PROMPTS list is a menu, PLAN permits the single-VM option, and no choice had been made.

ADR 0040 left "state the backup retention period" and ADR 0037/`docs/security-review.md` left SEC-R1, R6, R7, R8, R10 and R11
to this task.

## Decision

### Target: one Linux host running the production overlay
`infra/docker-compose.yml` + `infra/docker-compose.prod.yml` on any Linux VM with Docker and Compose v2. It is the simplest
option that is portable between providers, needs no provider-specific facts, and can be rehearsed completely on a laptop,
which a platform deployment cannot. Postgres (pgvector), Redis and RabbitMQ run in the stack. Object storage is external and
configured only by environment (Cloudflare R2 as in PLAN, or any S3-compatible store); so is SMTP. Moving Postgres, Redis or
RabbitMQ to managed services later changes `POSTGRES_HOST`, `REDIS_HOST` and `RABBITMQ_HOST` and drops the local service;
nothing in the application assumes the container.

Alternatives not taken: Fly.io, Railway or Render (each needs its own config format and account to test, and ties the
scripts to one provider's facts); Kubernetes (PLAN: only when traffic or team size justifies it); Docker Swarm secrets or SOPS
(extra tooling for one host: the env file is mode 0600 and checked by `preflight.sh`); Watchtower-style auto-update (no
approval gate); nginx plus certbot or Traefik (more moving parts than Caddy for the same result).

### Web stays in the stack, not on Vercel
The web app and the `/api/core/*` proxy (ADR 0011) are designed as one same-origin unit: cookies, CSRF origin checks, CSP,
rate-limit client address (trusted proxy chain) and the blocked-path rules all assume it. Vercel would need a public core-api
URL, a new trust model for the client address and CORS for credentials, none of which is tested. It is a later, separate change;
the web image is also what runs on a VM, so nothing is lost. Recorded as a deviation from the PROMPTS wording.

### Edge: Caddy, networks, no direct ports
Caddy terminates TLS with automatic certificates (HTTP to HTTPS redirect, HTTP/2 and HTTP/3), forwards only to `web:3000`, limits
request bodies, logs JSON with the client address truncated and token query parameters removed. It is the only container with
published ports (80, 443). Three networks: `edge` (Caddy, web), `app` (web, core-api, ai-service, ClamAV: has internet egress),
`data` (Postgres, Redis, RabbitMQ, backup: `internal`, no route out). core-api and ai-service sit on both `app` and `data`;
Caddy and web cannot reach the data stores. `/internal/**` and `/actuator/**` (except health) are not routed (web proxy rule,
tested in `smoke.sh`). Caddy is the one image that runs as root, with every capability dropped except `NET_BIND_SERVICE`, a
read-only root and no new privileges. HSTS comes from the application (only when the request reached it over TLS, which Caddy
guarantees through `X-Forwarded-Proto`).

### Images: built once, scanned, promoted, pinned
CI builds `core-api`, `ai-service`, `web` and `backup`, scans them (Trivy, HIGH and CRITICAL with a fix block the pipeline) and
publishes to GHCR as `sha-<commit>` (every push to `main`) and, for a release, retags the already-tested `sha-<commit>` image
as the version tag (`docker buildx imagetools create`), so production runs the bytes staging ran. The server never builds: it
pulls by tag. Base images and the data-store images are pinned by digest (SEC-R8); a weekly workflow lists tags that have moved.
`latest` is refused by `deploy.sh`.

### Deploy, migrations and rollback
`deploy.sh TAG` on the host: preflight of the env file (secrets present, long, not placeholders; public URL matches; no fake
LLM; scanner on; payment prices real; backups encrypted), pre-deploy backup, pull, `up -d --wait`, smoke test through the public
URL, and an automatic return to the previous tag when the stack does not become healthy or the smoke test fails.
Flyway runs when core-api starts (one instance). **Rule: every migration is backward compatible** (expand now, contract in a
later release), so the previous image runs against the new schema; that is what makes `rollback.sh` (application only) safe.
Restoring the database is the answer only when data is damaged or a release broke the rule, and costs the data written since the
backup (RPO up to 24 hours, or since the pre-deploy backup for a bad deploy).

### Backups and retention (resolves the placeholder in ADR 0040 and the privacy template)
- A sidecar runs `pg_dump` (custom format) daily at 01:40 UTC, takes a catch-up backup at start when none is younger than 24
  hours, verifies the dump can be listed and has table data, writes a SHA-256 file, encrypts it (AES-256-CBC, PBKDF2, passphrase
  from a file; **required** in production by `preflight.sh`), keeps it on a Docker volume and copies it to an S3-compatible
  bucket when `BACKUP_S3_URI` is set.
- **Retention: 30 days**, `BACKUP_RETENTION_DAYS`, strictly by age (no "keep the newest N": the period is a promise to users).
  Local files are pruned by the sidecar after each backup; off-host objects are pruned by the same script and should also
  have a bucket lifecycle rule (`infra/deploy/bucket-lifecycle.json`). The figure is stated in the privacy policy
  (`RETENTION_DAYS.backups`), `docs/compliance/data-inventory.md` and the DPA template; a test fails if they differ.
  So "deleted data leaves the backups within 30 days".
- The container health check fails when the newest daily backup is older than 30 hours (or the off-host copy is), so a
  stopped backup shows up in `docker compose ps` and in the uptime/health tooling, not months later.
- **Restore is tested, not assumed**: `restore.sh` restores into a scratch database or swaps a restored database in as the live
  one (refuses while connections are open, keeps the old database until the operator drops it). `roundtrip-test.sh` compares
  every table's row count and content hash between the live and the restored database (pgvector column included); it runs in
  CI against a throw-away Postgres (plain, encrypted, damaged file, wrong key, live swap) and in the local rehearsal against the
  real schema, plus a disaster drill (`rehearsal.sh restore-drill`).
- **Not provided, stated plainly**: point-in-time recovery (PLAN section 11 says "daily snapshots + PITR"). A self-hosted
  Postgres in Compose has snapshots (dumps) only; PITR needs WAL archiving or managed Postgres. This is the main reason to move
  Postgres to a managed service when the user count justifies it. Object storage (CVs, rendered files) has no backup of its own:
  enable the bucket's object versioning and a lifecycle rule that expires old versions within the retention period, if the
  provider supports them (confirm in its documentation; this ADR does not assume it). **Not implemented**: re-applying account
  deletions after restoring an older backup (ADR 0040); the runbook makes it a manual step with the support record as source.

### Observability in production
The Prometheus, Grafana and Tempo profile of ADR 0039 is a local aid (Grafana is anonymous admin) and is not deployed. Production
has JSON logs with trace ids (rotated, 10 MB x 5 per container), Sentry when DSNs are set, container health checks and the backup
freshness check. Alerting needs an external uptime monitor on `/api/core/actuator/health` (checklist). The overlay also sets
`MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED`: under Spring Boot 4.1 the `application.yml` key `management.otlp.tracing.export.enabled`
no longer switches span export off, and core-api kept calling a collector that is absent (found when the overlay was started).

### Security review items closed here
SEC-R1 (TLS and HSTS at the edge), SEC-R6 (Redis password), SEC-R7 (the data network has no egress and no route from the edge;
`/internal` still relies on the service token), SEC-R8 (digests), SEC-R10 (ClamAV by default; `none` needs an explicit second
switch), SEC-R11 (the overlay was started and smoke-tested, read-only roots and dropped capabilities included).

### Rehearsal
`infra/deploy/rehearsal.sh` runs the real overlay plus `docker-compose.rehearsal.yml` (Mailpit and the S3 mock instead of
providers, Caddy's local CA, no ClamAV, no job-source scheduler) in its own compose project with random secrets in a temporary
directory, on non-default ports. It exercises `deploy.sh` end to end, the smoke test, the round trip, the restore drill and an
automatic rollback. It proves the configuration and the procedures; it cannot prove a provider, a DNS name, a real certificate
authority, a registry or SSH.

## Consequences
- One host is a single point of failure and every release restarts the application containers for tens of seconds (no
  blue-green). Acceptable for launch; the scale-up path is managed Postgres, then a second app host.
- The owner provides: a host, a domain, a registry token, R2 and SMTP and LLM and payment accounts, GitHub environments with
  reviewers and secrets (`docs/launch-checklist.md`). Nothing in the repository carries a real value.
- Release rule for contributors: migrations stay backward compatible; a column or table is removed one release after the code
  stopped using it.
- A change to the backup period changes the privacy policy: edit `BACKUP_RETENTION_DAYS` and `RETENTION_DAYS.backups` together.
