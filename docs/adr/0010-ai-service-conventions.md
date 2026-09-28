# 0010. ai-service skeleton conventions

## Status
Accepted

## Context
P0.3 scaffolds `services/ai-service`. PLAN.md and CLAUDE.md fix the stack (FastAPI, uv,
provider interface, versioned prompts, service-token auth) but leave some details open.

## Decision
- **Auth on every route, including `/health`.** CLAUDE.md requires the service token on every
  route; `/health` is no exception. The Docker healthcheck sends the token from the container
  env. OpenAPI/docs routes are disabled. The token is compared in constant time, must be at
  least 32 characters, and is sent as `X-Service-Token`.
- **Model tiers, not model names.** Callers ask for `ModelTier.FAST` or `ModelTier.STRONG`;
  the provider maps tiers to `LLM_MODEL_FAST` / `LLM_MODEL_STRONG` from settings.
- **Usage record on every call.** Every provider returns an `LLMUsage` (user_id, feature,
  provider, model, tokens, cost_usd, latency_ms, prompt_version). Cost is computed from
  `LLM_PRICING` in config; a model without pricing records cost 0 and logs a warning.
  Structured calls return one record per attempt, since retries are billable too.
- **Structured output.** `generate_structured` validates against a Pydantic model and retries
  once, telling the model only the failing field paths/types; a second failure raises
  `LLMOutputValidationError` (→ 502 problem response).
- **FakeProvider is test-only.** It is injected through `create_app(provider=...)`, not selectable
  via `LLM_PROVIDER`, so a misconfigured deployment can't silently serve canned output.
- **Diagnostics route.** `POST /v1/diagnostics/llm` (prompt `diagnostics/v1`) is the one LLM
  route in Phase 0. It exercises the whole path (prompt file → provider → validation → usage)
  and lets a real deployment check its key. No untrusted input reaches the prompt.
- **RabbitMQ.** One robust aio-pika connection, started in the background at startup so HTTP
  is available while the broker comes up. `/health` returns 503 until the consumer is
  connected. Queues are durable; handlers ack via `message.process(requeue=False)` and never
  log message bodies. The scaffold queue is `ai.noop`.
- **Compose.** Published on `127.0.0.1` only. `AI_SERVICE_TOKEN` is required (`${VAR:?}`), so
  compose refuses to start without it instead of falling back to a default secret.

## Consequences
- core-api (P1.5) must send `X-Service-Token` and consume the `usage` list for the ledger (P3.1).
- Orchestrators need to send the header for probes; a TCP probe works as a fallback.
- Dead-lettering and retry policy for real queues are decided when the first real queue lands.
