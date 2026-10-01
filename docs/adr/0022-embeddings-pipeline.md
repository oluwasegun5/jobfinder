# 0022. Embeddings pipeline

## Status
Accepted

## Context
P2.5 gives every active job and every resume version a vector, so the later search and matching tasks (P2.6, Phase 4)
can recall by cosine similarity (`PLAN.md` sections 5, 6 and 7, decision D2). `PLAN.md` leaves open which provider,
which model and dimension, what text is embedded, how the vectors get from ai-service into the database, and how a
change of text or model is noticed. This ADR records those choices. Provider facts were checked on 2026-10-01 against
the Voyage AI documentation (docs.voyageai.com: embeddings guide, API reference and pricing page).

## Decision

### Provider, model and dimension
- **Voyage AI, model `voyage-4`, 1024 dimensions.** `PLAN.md` allows Voyage or OpenAI. ai-service depends only on
  `anthropic` today and `.env.example` held no embedding key; Anthropic has no embeddings endpoint and recommends
  Voyage, and Voyage offers 200 million free tokens per account on `voyage-4`, so the whole job corpus costs nothing
  to start with. List prices when the free tokens run out: `voyage-4-lite` $0.02, `voyage-4` $0.06, `voyage-4-large`
  $0.12 per million tokens. The `voyage-4` family takes 32K tokens of input and `output_dimension` of 256, 512, 1024
  (the default), 2048.
- **`voyage-4` over `-lite`:** a few thousand jobs and resumes are a few million tokens, so the extra cost is cents and
  recall quality is what the product sells. **1024 over 2048:** half the storage and index size, still well inside
  pgvector's 2000-dimension HNSW limit (2048 would not index at all).
- ai-service calls the REST endpoint (`POST https://api.voyageai.com/v1/embeddings`) with `httpx`, without a vendor
  SDK: one request shape, and it keeps the provider behind our own `EmbeddingProvider` interface (D7). Each request
  sends `model`, `output_dimension`, `truncation: true` and an `input_type`: **`document` for jobs, `query` for
  resumes** (a resume is what searches the job corpus).
- **Pinned in config, on both sides:** `EMBEDDING_MODEL` and `EMBEDDING_DIMENSION` (core-api `app.embeddings.*`,
  ai-service settings). They are not trusted to match by luck: core-api's `/inputs` answers with its pinned pair and
  ai-service refuses to embed if its own differ; core-api's `/results` answers `409 embedding_space_mismatch` to
  vectors labelled with another model or dimension.
- **The dimension is also part of the column type, `vector(1024)`** (migration V19). core-api checks the column
  against `EMBEDDING_DIMENSION` at startup and refuses to boot on a mismatch. So changing the dimension needs a new
  migration that clears the embeddings and retypes both columns, then a backfill. Changing only the *model* needs no
  migration: the stored `embedding_model` no longer equals the pinned one, every vector counts as stale, and
  `make embeddings-backfill` re-embeds them all (old vectors keep serving until each is replaced; mixing models in one
  index is wrong for ranking, so search should be paused or the backfill finished before relying on it).
- **A deterministic fake provider** (`EMBEDDING_PROVIDER=fake`, model name must start with `fake-`) makes the pipeline
  testable and runnable without a key: the vector is a unit-length function of the text's SHA-256, so equal texts get
  equal vectors and nothing else is true (similar texts are not close). It is the compose default; real matching
  quality needs `EMBEDDING_PROVIDER=voyage`, `EMBEDDING_MODEL=voyage-4` and `VOYAGE_API_KEY`. With provider `voyage` and
  no key, ai-service does not consume the embed queues at all (logged at startup), so messages wait in the broker
  instead of being dead-lettered one by one.

### What is embedded
Built by core-api (`EmbeddingTextBuilder`), the one place that knows the schemas:
- **Job:** `Title: ...`, `Company: ...`, then `description_text`.
- **Resume version:** headline, summary, each role (title at company) with its bullets, skills, education, projects
  and certifications from `structured`. **The contact block (name, e-mail, phone, links) is left out**: it says nothing
  about fit and has no business in a vector.
- Both are cut to `app.embeddings.max-input-chars` (24,000 characters, about 6K tokens, far under the model's 32K) at a
  word boundary and never inside a surrogate pair. With the default batch of 32 a request stays under 200K tokens,
  below the 320K per-request limit of `voyage-4`. Provider-side `truncation` is a backstop only.
- Job descriptions and resumes are untrusted text, but embedding is not instruction-following: the text is only turned
  into numbers, so no prompt-injection handling applies here (it does to the later LLM steps).

### Message format and who talks to whom
- Two durable queues, **`jobs.embed`** and **`resumes.embed`**, each with a dead-letter queue (`*.dlq`, same
  arguments on both sides, as the parse queue of ADR 0016). A message is `{"id": "<uuid>"}`: a job id or a
  `resume_versions` id. **It names, it does not carry**: no job or CV text goes through the broker (ADR 0016's rule).
- ai-service consumes both queues in batches (below). For each batch it calls two **internal core-api endpoints**,
  authenticated with the existing shared token (`X-Service-Token` = `AI_SERVICE_TOKEN`, now used in both directions):
  - `POST /internal/v1/embeddings/inputs` `{kind, ids}` returns `{model, dimension, inputType, items:[{id, userId,
    text, inputHash}], skipped:[{id, reason}]}`. Rows already current are skipped (`UP_TO_DATE`), as are gone rows
    (`NOT_FOUND`), expired jobs (`EXPIRED`) and resume versions with no content yet (`NO_CONTENT`).
  - `PUT /internal/v1/embeddings/results` `{kind, model, dimension, items:[{id, inputHash, embedding}], usage:[...]}`
    returns `{applied, stale, missing}`.
  - `POST /internal/v1/embeddings/backfill?scope=ALL|JOBS|RESUMES` (below).
- Why HTTP write-back rather than a result queue: the vector must be written under the same guard that checks the
  text has not changed, the caller learns the outcome synchronously, and the same channel already carries the text
  fetch. The cost is that core-api must be up for ai-service to make progress; it retries (below) and the queue holds
  the work meanwhile.
- **Internal, not public.** `/internal/**` has its own Spring Security filter chain (matched first) that accepts only
  the service token, compared in constant time; a user's JWT, even an admin's, does not open it and the service token
  does not open the user API. The paths are excluded from the OpenAPI document (`springdoc.paths-to-exclude`), so they
  are not in the generated web client, and no `/api/core` proxy route reaches them. **They must not be exposed at the
  public edge**: block `/internal/` there as well (defence in depth; the token is the only check inside core-api).

### Batching
ai-service collects up to `EMBEDDING_BATCH_SIZE` (32) messages per queue, or whatever has arrived
`EMBEDDING_BATCH_WAIT_SECONDS` (1 s) after the first, then makes one `inputs` call, one provider call and one
`results` call. A queue handles one batch at a time; the broker's prefetch window (at least twice the batch size)
keeps the next batch filling. Duplicate ids in a batch are embedded once and every copy acked.

### Idempotency and staleness
Delivery is at least once and a row can change while its vector is in flight, so everything is safe to repeat:
- Each row stores `embedding`, `embedding_model`, `embedding_input_hash` (SHA-256 of the template version and the exact
  text) and `embedded_at` (a CHECK keeps the four set or unset together).
- A row is **stale** when its hash differs from the hash of the text built from the row now, or its model differs from
  the pinned one. `inputs` leaves out rows that are not stale, so a redelivered message costs no provider call.
- `results` re-reads each row `FOR UPDATE` (in id order), rebuilds its text and writes only if that hash equals the one
  the vector was made from; otherwise it counts the item `stale` and writes nothing. The row lock serialises against the
  ingestion update: a content change commits either before (the stale vector is dropped) or after (its own hook then
  sees a hash mismatch and queues a fresh message). A stale vector can therefore never overwrite a newer one.
- `TEMPLATE_VERSION` is part of every hash. Changing the templates, the truncation budget or the provider input type
  means bumping it, which makes everything stale and lets the backfill rebuild.

### Publishing
- **Jobs:** `JobIngester` publishes `JobContentChanged(jobId)` (a public event of the ingestion module) after it creates,
  refreshes or merges a job. The embeddings module's `@TransactionalEventListener(AFTER_COMMIT)` reads the stored job,
  and queues it only if it is stale in the sense above: a re-fetch that changes nothing the vector depends on (the
  common case, every run) costs one read and no message. A job that becomes `EXPIRED` is not embedded; one that is
  reactivated is re-evaluated by the same event.
- **Resumes:** `ResumeVersionChanged(resumeVersionId)` (public event of profile) is published when a version gets its
  structured content: the parser filling the upload version (`ResumeParseStore.complete`) and a user's edit
  (`ResumeContentService.save`, new or updated EDIT version). A freshly uploaded version has no content until parsing
  finishes, so it is embedded then, not at upload. Deleting an account cascades to `resume_versions`, so its vectors go
  with it (`PLAN.md` section 9).
- The new `embeddings` module depends on `ingestion` and `profile` only through those two event types, and reads the
  few columns it needs (`jobs`, `companies`, `resumes`, `resume_versions`) itself, so neither module knows it exists.
  It is the only code that touches the embedding columns.
- **Consistency risk (not a transactional outbox):** the publish happens after the commit. If the process dies, or the
  broker is unreachable, between the commit and the publish, the row keeps a missing or stale embedding and nothing
  queues it. The failure is logged at ERROR and never thrown (the content change is already committed and must not be
  reported as failed). **The backfill is the repair**, and it is idempotent, so running it on a schedule or after any
  broker outage is the remedy; an outbox table was judged more machinery than a derived, recomputable value deserves
  (ADR 0016 made the same call for parsing). Reverse case: a message for a row that changed again is harmless, `inputs`
  rebuilds the text from the row at processing time.

### Failure handling, retries, dead letters
- ai-service retries a transient failure (core-api or the provider unreachable, 5xx, 429) up to
  `EMBEDDING_MAX_ATTEMPTS` (3) with exponential backoff from `EMBEDDING_RETRY_BACKOFF_SECONDS` (2 s). After that the
  messages are **rejected without requeue into `*.dlq`**; requeueing would spin on an outage. Nothing is lost: the DLQ
  only records which ids were affected, and the backfill re-queues every stale row, so the DLQ can simply be purged
  after the cause is fixed.
- A permanent failure (the provider refuses one input, a malformed message, a pinned-space mismatch, 4xx from core-api)
  is not retried. When it hits a batch of several, ai-service retries the rows one by one, so one bad row sends only
  itself to the DLQ. A **pinned-space mismatch dead-letters everything it touches, loudly (ERROR)**: fix the
  configuration, then run the backfill.
- The consumer never lets an exception leave a message unsettled; a crash mid-batch leaves the deliveries unacked and
  the broker redelivers them, which the idempotency above makes harmless.

### Usage
Each provider call produces usage records in the shape of the existing AI calls (user, feature `embed_job` or
`embed_resume`, provider, model, tokens, cost, latency), logged by ai-service and sent with the results so core-api logs
them in the same `ai usage ...` line as CV parsing. The billing ledger is Phase 3. Voyage reports one token total per
call, so a batch of resumes shares it out over their owners by text length (jobs are system work, no user). Cost comes
from the existing pricing table (`voyage-4` input $0.06 per million tokens, no output).

### Backfill
`make embeddings-backfill` (optionally `SCOPE=JOBS|RESUMES`) calls `POST /internal/v1/embeddings/backfill` on the running
core-api with the service token. It walks active jobs and resume versions with content in id order, a page at a time,
and queues every row with no vector, another model than the pinned one, or text whose hash differs from what was
embedded. It returns counts (`scanned`, `enqueued`) per kind. Running it twice, or while the queue is busy, is safe.
It is an internal endpoint, not an admin one, so it changes neither the public API nor the generated client; an admin
button can call the same service later. To skip the hashing for rows that cannot be stale, the page query only returns
rows that have no vector, another model, or were updated after they were embedded (`embedded_at < updated_at`); a job
changed by an app host whose clock runs behind the database's could slip past that prefilter, and the event-driven path
still covers it.

### Migration V19
Adds `embedding vector(1024)`, `embedding_model`, `embedding_input_hash CHAR(64)`, `embedded_at` and the all-or-none
CHECK to `jobs` and `resume_versions`, plus HNSW indexes with `vector_cosine_ops` on both embedding columns (default
`m=16`, `ef_construction=64`; to be tuned with real data in P2.6). `CREATE EXTENSION IF NOT EXISTS vector` is in the
migration: the compose and Testcontainers images are `pgvector/pgvector:pg16`, and a managed Postgres needs the
extension enabled (and the migration user allowed to create it) before the first deploy. `PLAN.md` section 5 also
lists `jobs.search` (tsvector), which is P2.6's migration, not this one.

## Consequences
- New jobs and resume versions are embedded automatically; the backfill covers history, outages and model changes.
- Embedding needs core-api up: ai-service cannot write results without it, and a mismatch of the two services'
  `EMBEDDING_MODEL`/`EMBEDDING_DIMENSION` stops the pipeline instead of silently storing the wrong space.
- Measured locally (one ai-service, the fake provider, compose on a laptop, live ingestion running alongside): about 45
  jobs a second end to end, 29,000 jobs embedded in about 15 minutes, almost all of it core-api's HNSW inserts and the
  JSON hop. A real provider adds its own latency per batch; more throughput means more ai-service replicas, which the
  queue and the row-level guard already allow.
- Every ingestion refresh costs one extra indexed read per job (the staleness check). If that shows up in profiles,
  compare `updated_at`/`embedded_at` first or batch the checks per run.
- Re-embedding all history after a model change is a billable batch job; at the volumes in `PLAN.md` it stays inside
  Voyage's free allowance.
- Not done here, on purpose: a search endpoint, similarity queries, near-duplicate merging by cosine (the nightly pass
  of `PLAN.md` section 6.5), matching and scoring, and any UI.
