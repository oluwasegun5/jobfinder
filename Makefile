COMPOSE := docker compose -f infra/docker-compose.yml --env-file .env

.PHONY: up down logs test fmt lint contract embeddings-backfill search-perf

up:
	$(COMPOSE) up -d --wait

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f

test:
	cd services/core-api && ./mvnw test
	cd services/ai-service && uv run pytest
	npm run web:test

fmt:
	cd services/ai-service && uv run ruff format . && uv run ruff check --fix .

lint:
	npm run web:lint
	cd services/ai-service && uv run ruff format --check . && uv run ruff check . && uv run mypy

contract:
	npm run contract:generate

# Queue every active job and resume version whose embedding is missing or stale (docs/adr/0022).
# Needs the stack running. SCOPE is ALL (default), JOBS or RESUMES.
embeddings-backfill:
	@token=$$(grep '^AI_SERVICE_TOKEN=' .env | cut -d= -f2-); port=$$(grep '^CORE_API_PORT=' .env | cut -d= -f2-); \
	curl -fsS -X POST -H "X-Service-Token: $$token" \
	  "http://localhost:$${port:-8080}/internal/v1/embeddings/backfill?scope=$(or $(SCOPE),ALL)"; echo

# The job search latency bar (docs/adr/0023-job-search.md): loads 20,000 generated jobs into a throwaway Postgres
# (Testcontainers; Docker must be running), runs a mix of searches and fails if p95 reaches 300 ms. Takes a few
# minutes; not part of `make test`. The table of p50/p95/p99 per query type is printed and kept in
# services/core-api/target/search-perf.txt.
search-perf:
	cd services/core-api && ./mvnw -B -ntp test -Pperf -Dtest=SearchPerformanceTests
