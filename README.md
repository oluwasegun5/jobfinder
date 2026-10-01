# JobFinder

AI-enabled job finding platform: aggregates jobs from many sources, matches them to candidates, tailors resumes and cover letters, assists with applying, tracks applications, and prepares users for interviews.

- `PLAN.md` — architecture, data model, phases (source of truth)
- `CLAUDE.md` — rules for Claude Code in this repo
- `PROMPTS.md` — ordered build tasks; start with P0.1

## Repository layout

See `PLAN.md` §4 for the full layout and rationale.

```
apps/web/            Next.js frontend
services/core-api/    Spring Boot modular monolith
services/ai-service/  FastAPI AI service
services/scraper-worker/  Playwright scraper (Phase 3+)
extension/            Chrome extension (Phase 5)
packages/api-contract/  OpenAPI spec + generated TS client
infra/                 docker-compose, Dockerfiles, deploy config
docs/adr/              architecture decision records
docs/runbooks/         operational runbooks
```

## Local development setup

Prerequisites: [Docker Desktop](https://www.docker.com/products/docker-desktop/) (with Compose v2) and `make`. To run tests and linters outside Docker you also need [uv](https://docs.astral.sh/uv/) (ai-service) and Node.js 22+ (web; run `npm install` at the repo root once).

> **Windows:** `make` isn't included by default. Install it with `winget install ezwinports.make`, or use WSL, where it's usually already available.

1. Copy the environment template and adjust values if needed:

   ```bash
   cp .env.example .env
   ```

2. Start the local infrastructure stack (Postgres + pgvector, Redis, RabbitMQ, S3-compatible object storage, Mailpit):

   ```bash
   make up
   ```

   This brings up all containers and waits for them to report healthy.

3. Useful commands:

   | Command | Effect |
   |---|---|
   | `make up` | Start all infra containers, wait for healthy |
   | `make down` | Stop and remove containers |
   | `make logs` | Tail logs for all containers |
   | `make test` | Run tests for every service |
   | `make fmt` | Format/lint every service |
   | `make contract` | Regenerate the TS API client from a running core-api (see [packages/api-contract](packages/api-contract/README.md)) |

4. Service UIs once the stack is up:

   | Service | URL | Credentials |
   |---|---|---|
   | Web app | http://localhost:3000 | — |
   | RabbitMQ management | http://localhost:15672 | from `.env` (`RABBITMQ_USER` / `RABBITMQ_PASSWORD`) |
   | Object storage (S3-compatible) | http://localhost:9000 | none (local dev only, see [ADR 0009](docs/adr/0009-local-object-storage.md)) |
   | Mailpit (caught emails) | http://localhost:8025 | — |

The web app reaches core-api only through `/api/core/*` on its own origin, proxied by Next.js (see [ADR 0011](docs/adr/0011-web-core-api-access.md)). For hot reload run `npm run web:dev` against the compose core-api. Jobs and resume versions are embedded in the background (provider, pinned model and re-embedding: [ADR 0022](docs/adr/0022-embeddings-pipeline.md)); `make embeddings-backfill` queues everything whose embedding is missing or stale, and the keyless default uses fake vectors until `EMBEDDING_PROVIDER=voyage` and `VOYAGE_API_KEY` are set. Signed-in users search jobs by keyword and filters, open a job, find similar jobs and save or hide jobs ([ADR 0023](docs/adr/0023-job-search.md)); `make search-perf` runs the 20k-job latency test, which the default test run skips. Admins (`ADMIN_EMAIL` / `ADMIN_PASSWORD`) manage the job sources at `/admin/ingestion`: health, last run, enable or disable, run now, run history, and an email to `INGESTION_ALERT_RECIPIENTS` when a source returns no jobs or most of its targets fail ([ADR 0024](docs/adr/0024-source-dashboard-alerts.md)). `ai-service` is internal only: it listens on `127.0.0.1:8000` and every route, `/health` included, requires the `X-Service-Token` header (see [services/ai-service/README.md](services/ai-service/README.md)).
