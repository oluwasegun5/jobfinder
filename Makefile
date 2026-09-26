COMPOSE := docker compose -f infra/docker-compose.yml --env-file .env

.PHONY: up down logs test fmt

up:
	$(COMPOSE) up -d --wait

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f

test:
	@echo "No service tests yet - added per service starting P0.2 (core-api), P0.3 (ai-service), P0.4 (web)."

fmt:
	@echo "No formatters configured yet - added per service starting P0.2 (core-api), P0.3 (ai-service), P0.4 (web)."
