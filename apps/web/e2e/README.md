# Web E2E (Playwright)

Runs against a production build of the web app (port 3100, `E2E_WEB_PORT`) and the compose backend (core-api on
`CORE_API_URL`, default `http://localhost:8080`; Mailpit for the verification emails). Start the backend first
(`make up`), then from the repository root: `npm run web:e2e`.

## Rate limits

core-api rate-limits signup and login per IP, and every run signs up fresh users from one address. To clear the counters
before a run, **opt in**:

```bash
E2E_RESET_RATE_LIMIT=1 npm run web:e2e
```

This deletes only the rate-limit keys (`rl:*`) of the compose stack the run talks to, with
`docker compose -p "${E2E_COMPOSE_PROJECT:-jobfinder}" ... exec redis redis-cli EVAL ...`; it never flushes the database.
Without the variable nothing in Redis is touched. (It replaces the old opt-out `E2E_SKIP_RATE_LIMIT_RESET`, which is
ignored now.) No CI workflow runs this suite yet; when one does, set `E2E_RESET_RATE_LIMIT=1` there.

## Isolated stack (another worktree, or a loaded machine)

Every worktree's compose file says `name: jobfinder`, so a plain `docker compose` from any of them addresses the same
containers and volumes as your main development stack. To test a branch without touching it, run the backend as its own
compose project with its own `.env` (different ports) and point the suite at it:

```bash
export COMPOSE_PROJECT_NAME=jobfinder-mybranch          # also what `docker compose up` below uses
docker compose -f infra/docker-compose.yml --env-file .env up -d --build --wait
E2E_COMPOSE_PROJECT=jobfinder-mybranch E2E_RESET_RATE_LIMIT=1 \
  CORE_API_URL=http://localhost:<CORE_API_PORT> E2E_WEB_PORT=<free port> \
  npm run web:e2e -- --workers=2
docker compose -f infra/docker-compose.yml --env-file .env down -v   # only with that project name set
```

Set `E2E_COMPOSE_PROJECT` (or `COMPOSE_PROJECT_NAME` for your own compose commands) whenever the stack is not the default
`jobfinder` one; the reset then deletes keys in that stack's Redis instead of the main one's.

## Slow machines

- Use `--workers=2` (`npm run web:e2e -- --workers=2`) on a loaded machine; the default runs one worker per core and the
  stack times out when the CPU is shared.
- Expect slow runs for about 15 minutes after a fresh `up`: core-api ingests and embeds jobs in the background, which
  competes with the tests for CPU and database time. Wait for it to settle or accept the longer timeouts.
