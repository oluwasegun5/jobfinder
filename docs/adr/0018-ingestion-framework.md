# 0018. Ingestion framework

## Status
Accepted

## Context
P2.1 builds the machinery that every job source plugs into: schedule, fetch, store raw, record the run.
`PLAN.md` §6 fixes the shape (`JobSourceAdapter`, per-source cron with jitter, ShedLock, Resilience4j retry /
rate limit / circuit breaker, `raw_job_postings`, `ingestion_runs`) but leaves open how a schedule is expressed,
which parts of fault tolerance count against a source's health, what the run counters mean before there is a
normalizer, and which parts of the `jobs` schema are fixed now versus when the normalizer is written.

## Decision

### Shape
- **Adapters are Spring beans.** A bean implementing `JobSourceAdapter` (public API of the `ingestion` module) is
  registered as a source on startup and scheduled; nothing else needs wiring. The contract is deliberately narrow:
  `fetch(target, since)` returns raw postings or throws `SourceFetchException`. Adapters do no normalizing,
  retrying, rate limiting or storing.
- **`IngestionService.runNow(code)`** is the module's one public entry point. The scheduler and the future admin
  dashboard (P2.7) both call it, so a manual run and a scheduled run behave identically. It works on a disabled
  source: "enabled" decides what the scheduler picks up, not what an admin may trigger by hand.
- **JDBC, not JPA.** The ingestion tables are written with `JdbcClient`: the writes are upserts with
  `ON CONFLICT`, jsonb payloads and guarded updates, which an entity model only gets in the way of. This follows
  `ResumeParseStore` in `profile`.

### Scheduling
- **Interval plus a stable jitter, not cron.** Each source has `intervalMinutes` (default 360) and
  `jitterSeconds` (default 900). A source is due when `last_run_at + interval + offset` has passed, where the
  offset is `hash(code) mod jitterSeconds`: sources with the same interval do not start in the same minute, and
  each source's rhythm is the same after a restart. This needs no `next_run_at` column and no cron parser. Cron
  can be added later if a source needs "weekdays at 06:00".
- **A polling tick** (every 60 s) asks which enabled sources are due and starts each on its own virtual thread,
  so a slow source never delays the others. `last_run_at` is set when a run ends, whatever its outcome, so a
  failing source waits its normal interval instead of being retried every minute.
- **ShedLock** (`shedlock` table, JDBC provider, database clock) holds one lock per source, `ingestion:<code>`.
  A second run of a source, from the scheduler or by hand, on this or another instance, is turned away
  (`runNow` returns empty), not queued. `lock-at-most-for` (30 min) bounds how long a crashed instance can block
  a source. When a run starts it first closes any run of that source still `RUNNING` as `FAILED` ("Interrupted"),
  which is safe because it holds the lock.
- Tuning lives in `sources.config` (flat keys: `intervalMinutes`, `jitterSeconds`, `requestsPerSecond`,
  `retryMaxAttempts`); a missing or unusable value falls back to the defaults in `app.ingestion.defaults`
  rather than stopping the scheduler. Secrets never go in `sources.config`; API keys come from the environment.
  The fault-tolerance objects are built once per source, so a change to its tuning applies after a restart.

### Fault tolerance
- **Resilience4j core modules, used programmatically.** The Spring Boot starter targets Boot 3's autoconfiguration;
  this project is on Boot 4. We need three objects per source and a composition order, which is a few lines of
  code and no autoconfiguration to fight.
- **Layers, outermost first:** circuit breaker, retry (exponential backoff), rate limiter. The limiter is
  evaluated on every attempt, so retries cannot exceed the source's quota; it blocks up to a timeout rather than
  failing.
- **Retryable versus permanent.** `SourceFetchException` is either. Only retryable failures (timeout, 5xx, 429)
  are retried and counted by the breaker, because they say the *source* is unwell. A permanent failure (a 404 for
  one retired board token, an unparseable response) is not retried and does not count: it says something about
  one *target*. Six dead boards must not stop a healthy one. The breaker records one outcome per target, after
  retries.
- **A failing target never stops the run.** It is counted as an error, the others carry on, and the run ends
  `PARTIAL` or `FAILED` (all targets failed). Each target's postings are stored in one transaction: whole or not
  at all.
- **Health** on the source: `HEALTHY` (no errors), `DEGRADED` (some targets failed), `FAILING` (all failed),
  `UNKNOWN` (never run, or no targets, so nothing was proven). P2.7 builds its alerts on this and on the run rows.

### What is stored, and what the counters mean
- **Raw postings** are one row per `(source_id, external_id)`. A refetch overwrites the payload and bumps
  `fetched_at` (so it also serves as "last seen" for the expiry rules of P2.2). `target_id` records which target a
  posting came from, so the normalizer can reach the company. This column is an addition to `PLAN.md` §5.
- **Run counters, until the normalizer exists:** `fetched` is postings received; `created` and `updated` are raw
  postings stored for the first time and refreshed; `expired` is 0. P2.2 re-points `created`, `updated` and
  `expired` at jobs. `ingestion_runs` also gets `status` and `error_summary`, additions to `PLAN.md` §5: an alert
  has to tell a failed run from a quiet one.
- **`since`** handed to an adapter is the *start* of the last run that ended `SUCCEEDED`, not the last run: after a
  `PARTIAL` or `FAILED` run, the targets that failed would otherwise lose the window they missed. Adapters that
  cannot fetch incrementally ignore it, which is safe because storing a posting twice is idempotent.
- **Error messages are stored and logged**, so adapters must not put credentials in them (an aggregator's request
  URL carries its API key). The adapter contract says so.

### Schema decisions in the P2.1 migrations
- The `jobs` and `job_sources` tables follow `PLAN.md` §5, with these deliberate gaps: no `embedding` column and
  HNSW index (P2.5), no `search` tsvector and GIN index (P2.6), and no `CHECK` on `work_mode`, `employment_type`
  or `seniority`, whose value sets the normalizer fixes in P2.2. Adding a constraint later is a backward-compatible
  migration; removing a wrong one is not.
- `companies.normalized_name` has an ordinary index, not a unique one: how companies are matched and merged is
  decided with the normalizer.
- The fake source used to test the pipeline lives in test code, so production never registers a pretend source.

## Consequences
- ATS and aggregator adapters (P2.3, P2.4) are one class each plus a WireMock fixture test; they inherit
  scheduling, locking, retry, rate limiting, circuit breaking, raw storage and run metrics.
- Micrometer records `ingestion.runs`, `ingestion.postings`, `ingestion.target.errors` and
  `ingestion.run.duration`, tagged by source, ready for the dashboards in P6.3.
- The breaker's state is in memory: it resets on restart and is not shared between instances. Each instance
  learns on its own that a source is down, at the cost of a few failed calls. Sharing it would take a store we do
  not need yet.
- Raw postings are not yet pruned. The 30-day retention job is P6.4; `raw_job_postings.fetched_at` is indexed
  for it.
- Nothing here is user data, so there is no `UserDeletionRequested` handler.
