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
  embeddings/      EmbeddingProvider protocol, VoyageProvider, FakeEmbeddingProvider (keyless),
                   core-api client, batching queue worker (docs/adr/0022)
  api/             routes: /health, /v1/diagnostics/llm, /v1/parse-resume
  workers/         aio-pika consumer: the embed queues (batched) and a no-op queue (ai.noop)
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
| `LLM_PROVIDER` | `anthropic` | `anthropic` or `fake`: a deterministic keyword heuristic that answers `/v1/score-matches` and `/v1/tailor-resume` (provider `fake`; the tailoring only reorders what the resume already says), for keyless local runs and evals. Never the default; CV parsing and diagnostics need `anthropic` |
| `SCORE_MATCHES_MAX_JOBS_PER_CALL` | `6` | jobs scored per model call; a request of up to 50 jobs is split under this and the character budget |
| `SCORE_MATCHES_MAX_INPUT_CHARS` | `24000` | input budget of one call (about four characters per token) |
| `SCORE_MATCHES_DESCRIPTION_CHARS` | `3000` | job descriptions are cut to this before prompting |
| `LLM_MODEL_FAST` / `LLM_MODEL_STRONG` | `claude-haiku-4-5` / `claude-sonnet-5` | PLAN.md §7 model routing |
| `LLM_PRICING` | haiku + sonnet-5 list prices | JSON `{model: {input_per_mtok, output_per_mtok}}` |
| `RABBITMQ_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_VHOST`, `RABBITMQ_ENABLED` | `localhost` / `5672` / `guest` / `guest` / `/`, `true` | |
| `EMBEDDING_PROVIDER` | `voyage` | `voyage` or `fake` (deterministic, keyless; model name must start with `fake-`) |
| `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION` | `voyage-4` / `1024` | the pinned embedding space; must equal core-api's, which also fixes the dimension in the `vector(n)` column |
| `VOYAGE_API_KEY` | unset | with provider `voyage` and no key the embed queues are not consumed |
| `EMBEDDING_BATCH_SIZE` / `_BATCH_WAIT_SECONDS` | `32` / `1.0` | texts per provider call; how long a partial batch waits |
| `EMBEDDING_MAX_ATTEMPTS` / `_RETRY_BACKOFF_SECONDS` | `3` / `2.0` | transient failures, then the message goes to `*.dlq` |
| `CORE_API_BASE_URL` | `http://localhost:8080` | core-api's internal endpoints; the same `AI_SERVICE_TOKEN` authenticates the calls |
| `JOBS_EMBED_QUEUE` / `RESUMES_EMBED_QUEUE` (and `_DLQ`) | `jobs.embed` / `resumes.embed` (`*.dlq`) | declared with the same dead-letter arguments as core-api |

## Endpoints

- `GET /health` → `{"status": "UP", "rabbitmq": "UP"}`; 503 while the consumer is not connected.
- `POST /v1/diagnostics/llm` `{"user_id": "<uuid>"}` → a real LLM round trip through the fast model,
  returns the usage records (tokens, cost, latency, prompt_version) core-api will write to its ledger.

- `POST /v1/parse-resume?user_id=<uuid>`: the body is the raw PDF or DOCX bytes (the format is sniffed, not
  trusted; max 6 MB). Returns `{structured, warnings, prompt_version, usage}` where `structured` is validated
  against `app/parsing/schema.py`, `warnings` lists skills/employers the CV text does not support, and `usage` has
  one record per LLM call. core-api calls this from its `resumes.parse` queue worker (docs/adr/0016).

- `POST /v1/score-matches` `{user_id, prompt_version, candidate, jobs[1..50]}` → `{prompt_version, model, results, usage}`:
  scores each job 0-100 for the candidate with a short list of strengths and gaps, using the versioned prompt
  `app/prompts/match_scoring/v<n>.md` that the request pins (unknown versions: 400 `unknown_prompt_version`). The
  candidate and the postings are untrusted text, delimited and bounded. Model output is validated strictly and retried
  once; a job the model still gets wrong comes back `status: failed` with an `error_code` and no score (core-api then
  serves its recall score, flagged unranked) and never fails the whole request. `usage` has one record per model call.
  core-api calls this for the matching engine (docs/adr/0026-matching-engine.md).

- `POST /v1/tailor-resume` `{user_id, prompt_version, resume, job, options}` → `{prompt_version, model, resume, changes, fact_check, job_text_redactions, usage}`:
  tailors a structured resume to a job with the strong model and the versioned prompt `app/prompts/tailor_resume/v<n>.md`
  that the request pins (unknown versions: 400 `unknown_prompt_version`). The job text is untrusted: limited, sanitised
  (instructions to an AI are redacted) and delimited with a random nonce; the contact block never reaches the model.
  `changes` are computed by code (unit, op, path, before, after, rationale). `fact_check` is the separate deterministic
  check of the result against the source resume (BLOCKING for an invented employer, school, degree, date range,
  credential, project or link; WARNING for a new skill, metric or number). A draft with BLOCKING flags is still returned.
- `POST /v1/fact-check` `{source, candidate, job_description?}` → the same `fact_check` object, with no model and no
  cost; core-api re-runs it on every edit of a draft (docs/adr/0029-resume-tailoring.md).

## Embeddings

core-api queues `{"id": "<uuid>"}` on `jobs.embed` / `resumes.embed` when a job or resume version is created or
materially changed. This service collects batches, asks core-api for the text of the stale ones
(`POST /internal/v1/embeddings/inputs`), embeds them in one provider call and stores the vectors
(`PUT /internal/v1/embeddings/results`). Nothing here exposes an HTTP route for it. See
[docs/adr/0022-embeddings-pipeline.md](../../docs/adr/0022-embeddings-pipeline.md) for the design, and run
`make embeddings-backfill` from the repo root to queue everything missing or stale.
Without a key, keyless local runs use `EMBEDDING_PROVIDER=fake EMBEDDING_MODEL=fake-embed-1024`.

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

To see how the matching engine's scores are distributed (run it when the prompt, the weights or the heuristic change;
keyless by default, and the sample output is kept in docs/adr/0026-matching-engine.md):

```bash
make match-eval                                          # from the repo root
uv run python -m evals.match_eval --json                 # the same numbers as JSON
ANTHROPIC_API_KEY=... uv run python -m evals.match_eval --provider anthropic   # real stage 3 (costs cents)
VOYAGE_API_KEY=... uv run python -m evals.match_eval --embedding voyage        # real stage-2 vectors
```

It runs the three matching stages over three invented candidates and 48 invented jobs in `evals/fixtures/match/`
(a Python mirror of core-api's stage-2 blend, checked against the same golden cases as the Java tests, then this
service's own scoring over the top 30 jobs) and prints per-stage histograms and percentiles, the Spearman rank
correlation between stage 2 and stage 3, and the share of jobs left unranked. With the fake provider the stage-3
numbers show the report's shape only; they say nothing about the prompt or a model.

If the response shape of `/v1/parse-resume` changes, regenerate the contract file core-api's tests stub with:
`UPDATE_CONTRACTS=1 uv run pytest tests/test_core_api_contract.py`.
