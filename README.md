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

Prerequisites: [Docker Desktop](https://www.docker.com/products/docker-desktop/) (with Compose v2) and `make`. To run ai-service tests and linters outside Docker you also need [uv](https://docs.astral.sh/uv/).

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

4. Service UIs once the stack is up:

   | Service | URL | Credentials |
   |---|---|---|
   | RabbitMQ management | http://localhost:15672 | from `.env` (`RABBITMQ_USER` / `RABBITMQ_PASSWORD`) |
   | Object storage (S3-compatible) | http://localhost:9000 | none (local dev only, see [ADR 0009](docs/adr/0009-local-object-storage.md)) |
   | Mailpit (caught emails) | http://localhost:8025 | — |

The `web` app is added to the compose stack in P0.4. `ai-service` is internal only: it listens on `127.0.0.1:8000` and every route, `/health` included, requires the `X-Service-Token` header (see [services/ai-service/README.md](services/ai-service/README.md)).
