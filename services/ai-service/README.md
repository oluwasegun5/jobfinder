# ai-service

Internal FastAPI service for all LLM work (PLAN.md §3, §7). Only core-api calls it.

## Layout

```
app/
  main.py          create_app(): wiring, lifespan, global service-token auth
  asgi.py          uvicorn entrypoint (app.asgi:app)
  config.py        Settings from env (model names, pricing, RabbitMQ, token)
  security.py      X-Service-Token dependency (applied to every route)
  llm/             LLMProvider protocol, AnthropicProvider, FakeProvider (tests),
                   generate_structured (Pydantic validation, one retry)
  prompts/         versioned prompts: <feature>/v<n>.md
  api/             routes: /health, /v1/diagnostics/llm
  workers/         aio-pika consumer (one no-op queue: ai.noop)
```

## Commands

```bash
uv sync                  # install
uv run pytest            # tests (use FakeProvider, no network)
uv run ruff format . && uv run ruff check . && uv run mypy
AI_SERVICE_TOKEN=$(openssl rand -hex 32) RABBITMQ_ENABLED=false uv run uvicorn app.asgi:app --reload
```

## Config

| Env var | Default | Notes |
|---|---|---|
| `AI_SERVICE_TOKEN` | — (required, ≥ 32 chars) | shared secret sent by core-api in `X-Service-Token` |
| `ANTHROPIC_API_KEY` | unset | LLM routes return 503 until set |
| `LLM_MODEL_FAST` / `LLM_MODEL_STRONG` | `claude-haiku-4-5` / `claude-sonnet-5` | PLAN.md §7 model routing |
| `LLM_PRICING` | haiku + sonnet-5 list prices | JSON `{model: {input_per_mtok, output_per_mtok}}` |
| `RABBITMQ_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_VHOST`, `RABBITMQ_ENABLED` | `localhost` / `5672` / `guest` / `guest` / `/`, `true` | |

## Endpoints

- `GET /health` → `{"status": "UP", "rabbitmq": "UP"}`; 503 while the consumer is not connected.
- `POST /v1/diagnostics/llm` `{"user_id": "<uuid>"}` → a real LLM round trip through the fast model,
  returns the usage records (tokens, cost, latency, prompt_version) core-api will write to its ledger.

Errors are RFC 7807 `application/problem+json`.
