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
  parsing/         CV parsing: text extraction, strict schema, grounding check, service
  api/             routes: /health, /v1/diagnostics/llm, /v1/parse-resume
  workers/         aio-pika consumer (one no-op queue: ai.noop)
evals/             hand-run evals against the real provider (not part of pytest)
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

- `POST /v1/parse-resume?user_id=<uuid>`: the body is the raw PDF or DOCX bytes (the format is sniffed, not
  trusted; max 6 MB). Returns `{structured, warnings, prompt_version, usage}` where `structured` is validated
  against `app/parsing/schema.py`, `warnings` lists skills/employers the CV text does not support, and `usage` has
  one record per LLM call. core-api calls this from its `resumes.parse` queue worker (docs/adr/0016).

Errors are RFC 7807 `application/problem+json` with a stable `code` and a `retryable` flag. For
`/v1/parse-resume`: `empty_file` (400), `unsupported_file_type` (415), `file_too_large` (413),
`unreadable_file` / `no_extractable_text` / `llm_refused` (422), `llm_output_invalid` / `llm_unavailable` (502),
`llm_not_configured` (503).

## Evals

`tests/` never calls a real LLM. To score the CV parser against the five synthetic CVs with the real provider
(costs a few cents; run it when the prompt, schema or model config changes):

```bash
ANTHROPIC_API_KEY=... AI_SERVICE_TOKEN=$(openssl rand -hex 32) uv run python -m evals.parse_resume
uv run python -m evals.parse_resume --only prompt_injection --min-score 0.9
uv run python -m evals.parse_resume --dir path/to/anonymised/cvs    # print parses for eyeballing
```

It prints per-fixture scores (contact, experience, education, skills F1), model cost, and exits 1 below
`--min-score` (default 0.85) or if the prompt-injection fixture leaks anything into the output.

If the response shape of `/v1/parse-resume` changes, regenerate the contract file core-api's tests stub with:
`UPDATE_CONTRACTS=1 uv run pytest tests/test_core_api_contract.py`.
