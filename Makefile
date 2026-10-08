COMPOSE := docker compose -f infra/docker-compose.yml --env-file .env

.PHONY: env up down logs observability-up observability-down observability-check test fmt lint contract embeddings-backfill search-perf match-eval deploy-check

# Creates .env from .env.example with a fresh random value for every change-me-* placeholder (never overwrites).
env:
	@test ! -e .env || { echo ".env already exists; not touching it"; exit 0; }; \
	while IFS= read -r line; do \
	  case "$$line" in \
	    *=change-me-*) printf '%s=%s\n' "$${line%%=*}" "$$(openssl rand -hex 24)";; \
	    *) printf '%s\n' "$$line";; \
	  esac; \
	done < .env.example > .env; echo "created .env with generated secrets"

up:
	$(COMPOSE) up -d --wait

down:
	$(COMPOSE) down

# The stack plus Tempo, Prometheus and Grafana, with span export on (docs/adr/0039-observability.md).
# Grafana is on localhost:3001 (GRAFANA_PORT), Prometheus on localhost:9090 (PROMETHEUS_PORT).
observability-up:
	OTEL_TRACING_EXPORT=true $(COMPOSE) --profile observability up -d --wait

observability-down:
	$(COMPOSE) --profile observability down

# Dashboards and scrape config are parsed and checked without Docker.
observability-check:
	python3 infra/observability/check.py
	cd infra/observability && python3 -m unittest -q test_check

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

# Score distribution of the matching engine over the synthetic fixtures (docs/adr/0026-matching-engine.md): per-stage
# histogram and percentiles, rank correlation between stage 2 and stage 3, share unranked. Keyless by default (the
# deterministic heuristic provider). Real stage 3: make match-eval ARGS='--provider anthropic' with ANTHROPIC_API_KEY
# set; real stage-2 vectors: ARGS='--embedding voyage' with VOYAGE_API_KEY.
match-eval:
	cd services/ai-service && uv run python -m evals.match_eval $(ARGS)

# The job search latency bar (docs/adr/0023-job-search.md): loads 20,000 generated jobs into a throwaway Postgres
# (Testcontainers; Docker must be running), runs a mix of searches and fails if p95 reaches 300 ms. Takes a few
# minutes; not part of `make test`. The table of p50/p95/p99 per query type is printed and kept in
# services/core-api/target/search-perf.txt.
search-perf:
	cd services/core-api && ./mvnw -B -ntp test -Pperf -Dtest=SearchPerformanceTests

# Static checks for the production deployment files (no Docker daemon needed except compose config).
deploy-check:
	python3 -m unittest discover -s infra/deploy/test -v
