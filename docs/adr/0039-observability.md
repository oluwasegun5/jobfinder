# 0039. Observability: OpenTelemetry traces, Prometheus metrics, Grafana as code, Sentry, redacted JSON logs

## Status
Accepted

## Context
PLAN.md section 11 asks for OpenTelemetry traces across web, core-api and ai-service, Prometheus metrics, Grafana
dashboards, Sentry for errors and structured JSON logs with request and trace ids. PROMPTS.md P6.3 adds "as code" for the
dashboards and "PII redaction" for the logs, and its acceptance test is that one request can be followed end to end
locally. P6.2 left a gap list for this task (SEC-R5): the rule "never log CV text, tokens or personal data" is kept only
by not logging them, and neither ai-service nor a proxy log is covered.

The default `docker compose up` must stay small (Docker VM of about 8 GB shared with other work), and no real third-party
service may be contacted from a test.

## Decision

### Traces
- Every service always creates spans, so every log line and every response can carry a trace id, but spans leave the
  process only when `OTEL_TRACING_EXPORT=true`. The default stack therefore needs no collector and logs no connection
  errors.
- W3C `traceparent` is the propagation format everywhere.
- **web**: `proxy.ts` puts a `traceparent` on every `/api/core/*` request before the Next rewrite forwards it: the span of
  the Next request when OpenTelemetry is running (`@vercel/otel`, registered in `instrumentation.ts`), else the caller's
  trace, else a new one. A malformed value is replaced, never forwarded. A browser request is therefore one trace from
  the Next server on.
- **core-api**: Spring Boot's OpenTelemetry starter (Micrometer tracing bridge, OTLP/HTTP exporter). The ai-service clients
  are built with the `ObservationRegistry`, so the context rides on every call to ai-service; the RabbitMQ template and
  listeners have observation on, so a trace continues through a queue (upload, then the parse a consumer runs).
  `TraceIdResponseFilter` sets `X-Trace-Id` on every response, security errors included. Spring Security's per-filter
  spans are switched off (noise).
- **ai-service**: `opentelemetry-instrumentation-fastapi` and `-httpx`, a tracer provider of its own per app, OTLP/HTTP
  exporter. `/health` and `/metrics` make no spans, and the per-message ASGI `send` and `receive` spans are dropped.
- Verified locally on the compose stack: one upload through the web port produced a single trace of web (`POST`,
  `middleware`) to core-api (`http post /resumes`, `/resumes.parse send`, `receive`, the worker, the HTTP client call) to
  ai-service (`POST /v1/parse-resume`), and the core-api and ai-service log lines of that request carry the same
  `traceId`.
- Tempo (single process, in memory) receives the spans locally. Sampling is `TRACING_SAMPLE_PROBABILITY` (default 1.0;
  production lowers it).

### Metrics
- core-api exposes `/actuator/prometheus`, ai-service `/metrics`. **Both answer only to the service token**
  (`X-Service-Token`, the existing `AI_SERVICE_TOKEN`): the actuator path joins `/internal/**` in the token-only filter
  chain, and ai-service's global dependency already covers every route. A user token, even an admin's, does not open them,
  and the web proxy still blocks `/actuator/**` except `health`. The scrape endpoint stays on the main port because moving
  the actuator to a management port would also move `/actuator/health`, which compose, the web status badge and the
  security tests rely on.
- PLAN.md section 11, metric by metric:

  | PLAN.md | Metric |
  |---|---|
  | ingestion jobs per source per day | `ingestion_postings_total{source,outcome}` (existed) |
  | source error rate | `ingestion_target_errors_total`, `ingestion_runs_total{status}` (existed) |
  | active jobs | `jobs_active` (gauge) |
  | match latency | `matching_latency_seconds{operation}` (histogram) |
  | AI cost per day per feature | `ai_cost_usd_total{feature,model}`, `ai_calls_total`, `ai_call_duration_seconds` (from the usage ledger) |
  | credits consumed | `credits_consumed_total{feature}` |
  | signup to first match | `funnel_users{stage="matched"}` over `{stage="registered"}` |
  | apply rate | `funnel_users{stage="applied"}` over `{stage="registered"}` |

  ai-service adds `ai_llm_calls_total`, `ai_llm_cost_usd_total`, `ai_llm_tokens_total`, `ai_llm_call_duration_seconds` and
  `ai_http_request_duration_seconds`.
- The funnel and `jobs_active` are gauges read from the database (each module reads its own tables), at most once a
  minute (`CachedGauge`), so they survive restarts and cover every instance. A failing query is "no sample", not a failed
  scrape. Tags are low-cardinality only: never a user id, request key or URL with ids.

### Dashboards as code
`infra/observability/grafana/dashboards/*.json` ("JobFinder product": the PLAN.md metrics; "JobFinder services": request
rate, errors, latency, LLM health) and the provisioning files are committed, read-only in Grafana (`allowUiUpdates:
false`). `make observability-check` (run in CI by `.github/workflows/observability.yml`) parses them, checks uids,
datasources, grid and PromQL brackets, that Prometheus scrapes both services with the token, and that the heavy services
are in the compose profile.

### Compose
Tempo, Prometheus (7 days) and Grafana are in the compose profile `observability` (`make observability-up`, which also
turns span export on). Everything binds to localhost. Grafana has no login, local use only: production dashboards are P6.5.
Prometheus reads the token from a compose secret built from `AI_SERVICE_TOKEN`.

### Logs
- core-api: Spring Boot structured logging (`ecs` JSON, one object per line, trace and span id from the MDC), chosen by
  `LOGGING_STRUCTURED_FORMAT_CONSOLE` (on in compose and in the `prod` profile, plain text for a local run).
  ai-service: the same shape from its own `JsonFormatter` (`LOG_FORMAT=json`). web: server errors are written as one JSON
  line by `onRequestError`.
- **PII redaction** is a second line of defence, not the rule: the rule remains "never pass such values to a logger"
  (`LogRedactionTests`). `PiiRedactor` (Java), `redaction.py` and `redact.ts` share one set of patterns and mask emails,
  JWTs, bearer and basic credentials, provider keys (Anthropic, Stripe, Paystack, Voyage, AWS, Google), the value of
  secret-named fields (`password`, `token`, `api_key`, `cookie`, `signature`, ...) and international phone numbers, and
  cap a message at 4000 characters, because free text such as a CV cannot be recognised by its shape and a pasted CV is far
  longer than any real message. In core-api it is a `StructuredLoggingJsonMembersCustomizer` over every string of the JSON
  line (message and stack trace); in ai-service a logging filter on the handler (message arguments included).
- Not redacted: the plain-text console format of a local core-api run (use JSON to get the redaction), and anything a
  third-party library writes straight to stdout.

### Sentry
Errors only, on in each service only when its DSN is set (`CORE_API_SENTRY_DSN`, `AI_SERVICE_SENTRY_DSN`,
`WEB_SENTRY_DSN`). No default PII, no request data, no tracing, no local variables, and a `before_send` hook removes the
request, user, host name, breadcrumbs and extras and redacts messages and exception texts. Events carry a `trace_id`
tag. core-api reports what its catch-all handler turns into a 500; web reports server errors through `onRequestError`;
ai-service uses the SDK's FastAPI integration. There is no browser-side Sentry SDK: it would need a CSP change and sends
more from the user's machine, and the server side sees every failing API call.

## Alternatives considered
- **OpenTelemetry Collector between the services and Tempo.** One more container for nothing the services cannot do
  themselves; add it with P6.5 if the production backend needs batching or credentials the services should not hold.
- **Loki for logs.** Logs are JSON with trace ids on stdout, which any shipper reads; a local Loki adds memory for little.
  `docker compose logs core-api ai-service | jq -R 'fromjson? | select(.traceId=="...")'` finds a trace's lines.
- **Sentry's Spring Boot starter and `@sentry/nextjs`.** More auto-configuration to keep scrubbed (and the Next plugin
  rewrites the build); an explicit `init` with one scrubber is smaller and testable.
- **A separate management port for the actuator.** See Metrics.

## Consequences
- Local traces: `make observability-up`, open Grafana (http://localhost:3001), Explore, Tempo, search by service or paste
  the `X-Trace-Id` of a response.
- Adding a metric means one counter or gauge in its module and, for a dashboard, a change to the JSON and
  `make observability-check`.
- Residual: access tokens still cannot be revoked early and `style-src` keeps `'unsafe-inline'` (SEC-R2, SEC-R3 of
  `docs/security-review.md`); neither is observability and both stay open.
