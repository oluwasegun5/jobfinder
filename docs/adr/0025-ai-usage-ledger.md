# 0025. AI usage ledger, daily cap and cost dashboard

## Status
Accepted

## Context
P3.1 (`PLAN.md` D8, section 5 billing, section 7 cost control): LLM cost is the main variable cost, so every AI call is
recorded against a user's credit ledger, each user has a hard daily cap, and an admin can see cost per feature per day.
`CLAUDE.md` already requires ai-service to return user, feature, model, tokens, cost, latency and prompt version for
every call. Until now core-api only logged them (ADR 0008 deferred the ledger to this task). `PLAN.md` fixes the table
shapes (`ai_calls`, `credit_ledger`) but leaves open the units, whether the balance is stored or derived, what the
cap measures, what happens to calls that fail or are redelivered, and how an asynchronous flow reports a block.

The AI calls that exist today: resume parsing (`POST /v1/parse-resume`, one LLM call plus at most one validation
retry), resume embeddings and job embeddings (the embeddings worker, written back through
`PUT /internal/v1/embeddings/results`).

## Decision

### The `billing` module
A new module with a small public API and an `internal` package. It depends on no other module (it reads `users` only to
resolve an owner); `profile`, `embeddings` and `admin` depend on it through three interfaces:

| Interface | Purpose |
|---|---|
| `AiUsageLedger.record(AiUsage)` | records one billed call and debits its owner, atomically and idempotently |
| `AiUsageGate.requireAllowance / allowance` | the pre-flight check against the daily cap (throws `AiDailyCapReachedException`) |
| `AiCostReports.report(from, to)` | the admin dashboard's read side |

There are no dependency cycles: billing never calls back into profile or embeddings.

### Data (migration `V22__ai_usage_ledger.sql`)
- **`ai_calls`**: one row per billed provider call: `request_key` (UNIQUE), `user_id` (nullable), `feature`, `provider`,
  `model`, `prompt_version`, `pricing_version`, `input_tokens`, `output_tokens`, `cost_micro_usd`, `latency_ms`,
  `status` (`SUCCEEDED`, or `FAILED` for a billed call whose output was discarded), `created_at`.
  `user_id` is NULL for system work (job embeddings) and after the account is deleted (cost history stays, the link to
  the person goes). `PLAN.md` names the cost column `cost_usd`; it is stored as **integer micro-dollars** so sums are
  exact, and ai-service's decimal dollars are converted (rounded half up) on the way in.
- **`credit_ledger`**: append-only, one line per debit: `user_id`, `delta` (credits, a debit is negative), `reason`
  (`AI_USAGE` today; grants and purchases arrive with Phase 6), `ai_call_id`, `balance_after`, `created_at`. A trigger
  rejects UPDATE; DELETE stays possible so account deletion can erase a user's lines.
- **Balance is cached on the line, not derived on read.** Each debit takes a per-user transaction lock
  (`pg_advisory_xact_lock` on the user id), reads the user's previous `balance_after` and inserts the next line, so
  concurrent debits for one user run one after another and the chain is exact (a test runs 32 racing writers and
  checks every line against the running sum). Different users never wait for each other. Reads (the cap, the
  dashboard) never need the balance. The balance starts at 0 and, until plans grant credits, goes negative as usage
  accumulates: it means "credits consumed", and the cap, not the balance, limits spending in this task.
- **Credits** are the provider cost over a configured price: `credits = cost_micro_usd / app.billing.micro-usd-per-credit`
  (default 1000: a credit is a tenth of a cent), six decimals. Calls that cost nothing (the fake providers) get a
  `ai_calls` row but no ledger line.

### Prices: pinned, versioned, in config
No price is in code. ai-service computes `cost_usd` from `LLM_PRICING` (a JSON object per model, USD per million input
and output tokens; the defaults in `app/config.py` carry a "checked on" comment) and stamps every usage record with
`LLM_PRICING_VERSION` (a label, default `2026-10-01`) that is stored in `ai_calls.pricing_version`. Changing a price
means changing the table and bumping the version label together, so an old row can always be traced to the prices that
produced it. core-api does not recompute: ai-service is the one place that knows what the provider charged.

### Every ai-service result carries usage, and failures too
- `POST /v1/parse-resume` already returned `usage[]` (one entry per LLM call). Each entry now also carries `call_id`
  (a UUID per provider call) and `pricing_version`.
- The embeddings write-back's `usage[]` entries gain `callId` and `pricingVersion`. Job embeddings have `userId: null`;
  resume embeddings are split per owner by text length (one provider call, one total from the provider).
- **Partial failure keeps the bill.** A validation retry that fails, a refusal, or a second attempt that errors used to
  lose the first attempt's tokens. `LLMError` now carries the completed calls (`usage`), and every LLM problem
  response (RFC 7807) includes `usage[]` next to `code` and `retryable`. core-api records them as `FAILED`.
  If the embeddings write-back cannot be stored at all (vectors refused, core-api unreachable after retries), ai-service
  reports the already-billed usage on its own through `PUT /internal/v1/billing/usage`; if even that fails the figures
  are logged at ERROR (`UNRECORDED ai usage ...`) so the spend can be reconciled by hand.
- The embeddings write-back records usage **in the same transaction as the vectors**: both are stored or neither is,
  and ai-service's retry repeats both.

### Idempotency
`request_key = "ai-service:" + call_id`, UNIQUE. Recording is `INSERT ... ON CONFLICT (request_key) DO NOTHING`;
only the writer whose insert succeeded debits, and a partial unique index on `credit_ledger (ai_call_id)` makes a
double debit impossible even for a writer that bypasses the check. A redelivered write-back, a retried HTTP call or a
replayed message therefore records and charges once (tests: repeated results, repeated `/billing/usage`, racing
writers). A genuinely new provider call (a parse retried after a crash) has a new `call_id` and is rightly a new
charge. A sender that omits `callId` (an older ai-service) gets a key derived from the write itself (kind, record, and
the ids and hashes it stores), which is identical when the same write is repeated.
Usage of an account deleted a moment earlier is still recorded, without an owner, instead of failing on the foreign key.
If the ledger is down while a parse succeeded, the parse is not failed (the work was paid for and is good): the usage
is logged at ERROR with the same `UNRECORDED` marker.

### The daily cap
- **One total per user per UTC day**, in credits (`app.billing.daily-cap-credits`, default 500, i.e. $0.50; `0` turns the
  cap off). `PLAN.md` says "hard per-user daily caps" without splitting by feature, so there is no per-feature cap yet.
  What the user has spent today is `-sum(delta)` of today's `AI_USAGE` lines, so the cap and the ledger cannot disagree.
  The day is a UTC calendar day: it resets at 00:00 UTC, and the error says exactly when.
- **Pre-flight, not post-hoc.** `AiUsageGate.requireAllowance` runs BEFORE a user-attributed call. A call that crosses the
  line is allowed and recorded in full; the next one is blocked. Calls in flight are not counted until their usage is
  recorded, so concurrent calls can overshoot the cap by what they cost: bounded by (parallel calls x cost per call),
  and accepted over holding a reservation row per call, which a crashed worker would leave behind.
- **Who is capped.** Resume parsing (triggered by the user) and resume embeddings (attributed to the owner). Job
  embeddings are system work: `user_id` NULL, never capped, never debited, but recorded and in the dashboard.
- **The error.** HTTP 429, RFC 7807, `code: ai_daily_cap_reached`, a `resetsAt` member (ISO-8601 UTC) and a
  `Retry-After` header. `ApiException` gained `withProperty` for the extra member.
- **Async flows.** Parsing is asynchronous, so the check is in the parse worker before every attempt (a retry after a
  transient failure re-checks). A capped user's resume is marked `FAILED` with `parseError = ai_daily_cap_reached` (the
  existing status the web app already polls and shows), no ai-service call is made and the message is acked. It is
  **retryable after the reset** through `POST /resumes/{id}/reparse` (202, back to `PENDING`, queued again); the same
  endpoint pre-flights the cap synchronously, so asking too early is a 429 with the reset time and queues nothing. It is
  only allowed for failures that are not the file's fault (`ai_daily_cap_reached`, `parser_unavailable`,
  `parse_queue_unavailable`); anything else is 409 `reparse_not_allowed`.
  Resume embeddings: `/internal/v1/embeddings/inputs` leaves a capped owner's version out with the skip reason
  `AI_DAILY_CAP_REACHED` (the message is acked; nothing is retried in a loop). It is embedded by the next embeddings
  backfill (`make embeddings-backfill`) after the reset. A scheduled post-reset backfill is deliberately left out of
  this task.
- **User-facing allowance.** `GET /billing/allowance` (signed-in, always about the caller) returns cap, used,
  remaining, `resetsAt` and `exhausted`. It exists because the cap error needs a reset time on the resume screen; there
  is no other user billing UI.

### Admin cost dashboard
`GET /admin/billing/costs?from=YYYY-MM-DD&to=YYYY-MM-DD` (ADMIN only through the existing `/admin/**` rule: 401 without
a token, 403 for a user) returns totals plus `byFeature`, `byDay`, `byModel` and `byDayFeature` rows (`calls`,
`failedCalls`, `inputTokens`, `outputTokens`, `costUsd`). Both dates are UTC days and inclusive; the default is the last
seven days; at most 366 days (`invalid_range`, 400). No user is identified and no content is included. The web admin
page `/admin/billing` follows the ingestion pages (ADMIN gating in the UI, a date range, a table per grouping).

### Account deletion
`UserDeletionRequested` is handled in billing: the user's `credit_ledger` lines are deleted and their `ai_calls` lose
`user_id` (the cost history is the business's and holds no content).

## Consequences
- Every existing AI call is recorded: parsing (success and billed failures), resume embeddings (per owner) and job
  embeddings (system). Later features record through the same `AiUsageLedger` and check `AiUsageGate`.
- `ai_calls.cost_micro_usd` is `cost_usd * 1e6`; anything reading the `PLAN.md` column name must convert.
- The balance is "credits consumed so far" until Phase 6 adds grants; the cap is the only limit. When plans arrive a
  `reason` such as `PLAN_GRANT` is added by a new migration (the CHECK is deliberately narrow).
- A cap overshoot of at most the in-flight cost is possible; documented and accepted.
- Capped resume embeddings wait for a backfill; capped parses wait for the user's (or a later task's) reparse.
- `/internal/v1/billing/usage` is service-token only and excluded from the public OpenAPI document.
