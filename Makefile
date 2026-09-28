COMPOSE := docker compose -f infra/docker-compose.yml --env-file .env

.PHONY: up down logs test fmt lint contract

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
