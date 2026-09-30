# 0016. CV parsing pipeline

## Status
Accepted

## Context
P1.5 turns an uploaded CV into structured JSON (`resume_versions.structured`) and moves
`resumes.parse_status` from `PENDING` to `PARSED` or `FAILED`. `PLAN.md` fixes the ingredients (pdfplumber /
python-docx, an LLM behind the provider interface, a strict Pydantic schema with one retry, untrusted-input
handling, usage metering) but leaves open who consumes the queue, how a redelivered message is handled, and how
much of the model's output is trusted.

## Decision

### Shape of the pipeline
- **core-api owns the queue.** Upload commits, then (after commit) publishes `{resumeId, userId, versionNumber}` to
  the durable queue `resumes.parse`. A core-api listener consumes it, reads the file from object storage, and calls
  ai-service `POST /v1/parse-resume?user_id=…` with the raw file bytes and the service token. ai-service stays a
  stateless, storage-free service; it never holds storage credentials, and no CV content ever travels through the
  broker. The route is `/v1/parse-resume` to match the `/v1/…` convention of ai-service.
- **The message names, it does not carry.** Nothing personal is in the queue.
- **Broker down at upload time** marks the resume `FAILED` (`parse_queue_unavailable`) at once, rather than
  leaving it `PENDING` forever. There is no outbox table; the user can upload again.

### Idempotency (at-least-once delivery)
Parsing never inserts a `resume_version`: version 1 already exists (created by the upload, `structured = NULL`).
Every write is a guarded update in `ResumeParseStore`:
- resume: `PENDING → PARSED/FAILED` only `WHERE parse_status = 'PENDING'`;
- version: fill `structured` only `WHERE version_number = <message's> AND source = 'UPLOAD' AND structured IS NULL`.

So a redelivery is either skipped before any LLM cost (resume already `PARSED`/`FAILED`, gone, or owned by someone
else) or loses the write race and changes nothing. A later version (a user's edit) is never touched and
`UNIQUE (resume_id, version_number)` can never be hit. A `FAILED` parse is not resurrected by redelivery. Every
outcome is acked; only a message that cannot be read is rejected into `resumes.parse.dlq`, and unexpected
exceptions become `FAILED (unexpected_error)` so a bug cannot cause a redelivery loop. `Resume.parseStatus` and
`parseError` are `updatable = false` in JPA so a stale entity saved later cannot revert a stored result.
Two consumers racing on one message can both call the LLM (bounded by consumer concurrency); only one write lands.

### Failure reasons and retries
`resumes.parse_error` (V9) holds a stable code, only from a fixed list (`ParseFailureReason`); codes reported by
ai-service are mapped through an allow-list, never stored verbatim. Bad files and unusable model output
(`unsupported_file_type`, `file_too_large`, `empty_file`, `unreadable_file`, `no_extractable_text`, `llm_refused`,
`llm_output_invalid`) fail immediately; connection errors, timeouts, 429/5xx are retried in the worker (3 attempts,
exponential backoff from 2 s) and then fail as `parser_unavailable`. A wrong service token or an unconfigured
LLM fails at once as `parser_unavailable` (retrying cannot help).

### The model's output is untrusted
- **Strict schema, nothing self-referential.** `ParsedResume` has `extra="forbid"`, strict types, bounded lists
  and strings, control characters stripped, partial ISO dates only, and http(s)-only links. It has no score,
  confidence, "verified" or similar field, and any extra key (`score`, `verified`, …) fails validation, so a
  model talked into "adding" a self-assessment is rejected (one retry, then a 502 `llm_output_invalid`). Only field
  locations, never the model's own output, go back in the retry message.
- **Independent grounding check.** After validation, skills not present in the CV text are dropped and employers,
  schools and projects not present are flagged, returned as `warnings` for the review UI. This does not depend on
  anything the model claims.
- **Delimited data.** The CV goes in a block whose BEGIN/END markers carry a random per-request token; marker
  lookalikes in the CV are defused; the only trusted instruction comes after the block. No tools, no side effects.
- **Nothing acts on the output.** It is stored as inert JSON and shown to the user to review and correct (P1.6).
  `schema_version` is set by code, not the model. core-api checks only the envelope (object, `schema_version` 1,
  size cap), not the content.
- Limits (honest): an injected CV can still make a model fill *allowed* fields with text taken from the CV itself
  (grounding cannot see that). That is why review by the user is part of the flow.

### Data recorded
`resume_versions.model` and `prompt_version` are stored with `structured` (PLAN §7). ai-service returns one usage
record per LLM call (a validation retry is a second call). core-api logs them (no CV content); persisting them to
the credit ledger belongs to Phase 3 billing (ADR 0008) and is not built here.

### Extraction and prompt
pdfplumber (first 15 pages) and python-docx (paragraphs and tables in document order), run off the event loop, with
the format sniffed from content, a 6 MB cap, a ZIP entry-count/size guard for DOCX (macro-enabled files refused),
and 60k characters of text at most. Prompt `parse_resume/v1`, fast model tier, `max_tokens` 8192.

## Consequences
- A scanned or image-only PDF fails as `no_extractable_text`; OCR (mentioned in PLAN §7) is not built.
- There is no re-parse endpoint yet; a failed CV is re-uploaded.
- `docker compose up` now needs ai-service reachable by core-api and RabbitMQ healthy before core-api starts.
- Tests: ai-service uses the fake provider with five synthetic CVs rendered to real PDF/DOCX (`tests/fixtures`);
  `evals/parse_resume.py` scores a real provider against them by hand. A contract file
  (`core-api/src/test/resources/ai-service/parse-resume-ok.json`) is pinned by an ai-service test and used as the
  WireMock body in core-api tests, so both sides agree on the response shape.
