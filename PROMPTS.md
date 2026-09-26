# PROMPTS.md — Claude Code task sequence

Run these in order, one per session and branch. Each starts with the same preamble:

> Read PLAN.md and CLAUDE.md first. Then do the task below. Stop when the "Done when" criteria pass and give me a short summary.

Review every PR (especially migrations and API contracts) before running the next prompt.

---

## Phase 0 — Foundations

### P0.1 Monorepo + local stack
Create the monorepo layout from PLAN.md §4. Add `infra/docker-compose.yml` with Postgres 16 + pgvector, Redis, RabbitMQ (management UI), S3-compatible object storage (Adobe S3Mock, bucket auto-created — see ADR 0009), Mailpit. Add a root `Makefile` (`up`, `down`, `logs`, `test`, `fmt`) and `.env.example`. Add `.gitignore`, `.editorconfig`, README with setup steps.
**Done when:** `make up` starts all infra containers healthy.

### P0.2 core-api skeleton
Spring Boot (Java 21, Maven wrapper) in `services/core-api` with Spring Web, Security, Data JPA, Validation, Flyway, Actuator, Spring Modulith, springdoc-openapi, Testcontainers. Create empty module packages from PLAN.md §4 with `package-info.java`. Global ProblemDetail handler. Health endpoint. Modulith verification test. Dockerfile (layered jar). Add to docker-compose.
**Done when:** `/actuator/health` is UP in compose; tests (incl. module verification) pass.

### P0.3 ai-service skeleton
FastAPI in `services/ai-service` using uv, Ruff, mypy, pytest. `app/llm/` with a `LLMProvider` protocol, an Anthropic implementation and a fake provider for tests; model names from settings. Service-token auth dependency. `/health`. RabbitMQ consumer scaffold (aio-pika) with one no-op queue. Dockerfile. Add to compose.
**Done when:** health UP in compose; a test calls a route through the fake provider.

### P0.4 web skeleton + API contract
Next.js app in `apps/web` (TypeScript strict, Tailwind, shadcn/ui, TanStack Query). `packages/api-contract`: script that pulls OpenAPI from core-api and generates a typed client (openapi-typescript + openapi-fetch). Landing page + app shell layout. Dockerfile + compose.
**Done when:** web runs in compose and calls core-api health through the generated client.

### P0.5 CI + ADRs
GitHub Actions: per-service lint/test/build jobs with path filters, Docker build check, contract-staleness check, Dependabot, CodeQL. Write ADRs 0001–0008 for decisions D1–D8 in PLAN.md.
**Done when:** CI green on the PR.

---

## Phase 1 — Accounts & profile

### P1.1 Identity: email auth
`identity` module: users, refresh_tokens migrations; signup, login, logout, refresh (rotation + reuse detection), email verification, forgot/reset password (emails via Mailpit locally), rate limiting on auth endpoints (Redis, bucket4j). JWT access tokens 15 min. Integration tests for every flow incl. token reuse attack.
**Done when:** all auth flows pass tests; refresh token is an httpOnly cookie.

### P1.2 Identity: Google sign-in + account deletion
Google OAuth login linking to existing email accounts. `DELETE /me` that emits a `UserDeletionRequested` event; every module that stores user data must handle it (for now: identity + a test proving the event fires). Admin role seeding.
**Done when:** Google login works locally; deletion removes identity data and test proves the event.

### P1.3 Web auth UI
Signup, login, verify-email, forgot/reset password, Google button, protected route handling, silent refresh, logout. E2E Playwright test for signup → login.
**Done when:** E2E passes.

### P1.4 CV upload + storage
`profile` module: profiles, preferences, resumes, resume_versions migrations. Upload endpoint (PDF/DOCX, ≤ 5 MB, content-type sniffing) to object storage with private keys; pre-signed download URLs. List/delete/set-primary. Deletion handler for UserDeletionRequested (DB + storage).
**Done when:** upload/download/delete tested against S3Mock via Testcontainers (S3MockContainer).

### P1.5 CV parsing
ai-service: `/parse-resume` — extract text (pdfplumber, python-docx), LLM to strict Pydantic schema (PLAN.md §7), prompt v1, record usage. core-api calls it async via RabbitMQ after upload, stores structured JSON in resume_versions, updates parse_status. Include 5 synthetic CV fixtures + tests with the fake provider, and an eval script for real-provider runs.
**Done when:** uploading a fixture CV results in a parsed resume_version; failures set parse_status=FAILED with a reason.

### P1.6 Profile & preferences UI
Onboarding flow: upload CV → review/edit parsed profile → set preferences. Profile page to edit anytime. Resume list with primary selection.
**Done when:** a new user completes onboarding end to end (E2E test).

---

## Phase 2 — Job aggregation & search

### P2.1 Ingestion framework
`jobs` + `ingestion` modules: sources, source_targets, ingestion_runs, raw_job_postings, companies, jobs, job_sources migrations (PLAN.md §5). `JobSourceAdapter` interface, scheduler with ShedLock, Resilience4j retry/rate-limit/circuit breaker per source, raw storage, run metrics. A fake adapter to test the pipeline.
**Done when:** pipeline runs end-to-end with the fake adapter and records an ingestion_run.

### P2.2 Normalization + dedup + expiry
Normalizer: HTML→text, location parsing (city/country/remote), work mode, employment type, seniority from title, salary normalization. Fingerprint dedup + job_sources merge. Expiry rules from PLAN.md §6. Heavy unit tests with messy real-world-shaped samples.
**Done when:** tests cover ≥ 30 normalization cases and dedup/expiry scenarios.

### P2.3 ATS adapters
Greenhouse, Lever, Ashby, Workable, SmartRecruiters, Recruitee adapters. Verify each current public endpoint and response shape first; record in an ADR. WireMock fixtures per adapter. Seed file of ~200 company board tokens (tech companies hiring remotely + companies hiring in Africa) and an admin endpoint to add targets.
**Done when:** each adapter has fixture tests; a local run ingests real jobs from the seed list.

### P2.4 Aggregator adapters
Adzuna, JSearch (RapidAPI), Remotive, Arbeitnow, RemoteOK. Respect quotas via per-source config; follow each API's attribution terms (store and display source attribution). WireMock tests.
**Done when:** adapters tested; enabling keys in `.env` ingests real jobs.

### P2.5 Embeddings pipeline
ai-service worker: consume `jobs.embed` and `resumes.embed` queues in batches, call embedding provider, write back via core-api internal endpoint (or return message). pgvector columns + HNSW indexes. Backfill command. Embedding model + dimension pinned in config.
**Done when:** new jobs and resume versions get embeddings automatically; backfill works.

### P2.6 Search API + UI
Search endpoint: keyword (tsvector), filters (location, country, work mode, employment type, seniority, salary, posted within, company), semantic mode ("similar jobs"), cursor pagination. Job detail endpoint. Save/hide job. Web: search page with filters, job detail page, saved jobs.
**Done when:** p95 < 300 ms on 20k seeded jobs (add a perf test with generated data).

### P2.7 Admin source dashboard
Admin pages: sources list with health, last run, counts, enable/disable, trigger run; ingestion run history; alert (email to admin) when a source returns 0 jobs or error rate > 20%.
**Done when:** disabling a source stops its schedule; alert fires in a test.

---

## Phase 3 — AI matching

### P3.1 AI usage ledger
`billing` module: ai_calls + credit_ledger migrations. Every ai-service response includes usage; core-api records it and debits credits. Per-user daily cap (config). Admin cost dashboard (by feature/day/model).
**Done when:** every existing AI call (parsing, embeddings) is recorded; cap blocks further calls with a clear error.

### P3.2 Matching engine
`matching` module: stage 1 SQL filters from preferences, stage 2 pgvector recall + skill overlap + recency blend (weights in config), stage 3 LLM rerank of top 30 via ai-service `/score-matches` (prompt v1: score + strengths + gaps JSON). Cache in match_scores keyed by resume_version/job/prompt_version. Nightly batch for active users; on-demand for opened jobs.
**Done when:** tests prove filters, caching and invalidation on resume change; eval script reports score distribution on fixtures.

### P3.3 Feed UI + feedback
"For you" feed with score badge, strengths/gaps expandable, save/hide/applied actions recorded in user_job_actions and fed back into ranking (simple boosts/penalties by company/title similarity).
**Done when:** hiding a job removes it and demotes similar ones (test).

### P3.4 Digests & alerts
`notifications` module: saved searches with frequency, daily/weekly digest email (top new matches), instant alert for score ≥ threshold, unsubscribe links, notification preferences.
**Done when:** digest job sends correct emails to Mailpit in an integration test.

---

## Phase 4 — Documents & tracker

### P4.1 Resume tailoring
ai-service `/tailor-resume` (strong model, prompt v1) with guardrails from PLAN.md §7, plus a separate fact-check step that flags any entity not in the source resume. core-api `documents` module stores drafts; approve endpoint. Tests include adversarial fixtures (JD with injected instructions; model output with invented employer) that must be caught.
**Done when:** adversarial tests pass; approved docs are immutable.

### P4.2 Document rendering
ATS-friendly resume template (single column) and a styled template; render to PDF and DOCX; store in object storage; pre-signed download.
**Done when:** generated PDFs extract back to clean text in the right order (test).

### P4.3 Cover letters + application pack
Cover letter generation (tone: formal/warm/concise, length options), editable. Application pack endpoint: tailored CV, cover letter, answers to common screening questions from profile/preferences.
**Done when:** pack is generated for a fixture job; edits persist.

### P4.4 Tailoring UI
Job page → "Tailor for this job": side-by-side diff, accept/reject per change, fact-check warnings shown prominently, approve, export. Cover letter editor. Assisted apply: open apply URL + copy answers + "I applied" confirmation creating a tracker entry.
**Done when:** E2E from job to approved CV + tracker entry.

### P4.5 Application tracker
`applications` module: applications, application_events, reminders. Kanban board UI with drag-and-drop status changes, notes, manual entries for jobs found elsewhere, follow-up reminders (email), AI follow-up email draft.
**Done when:** status history recorded; reminder email sent in test.

---

## Phase 5 — Interview prep & extension

### P5.1 Interview question generation + company brief
`interview` module + ai-service endpoints: likely questions (behavioral, technical, role-specific) from JD + profile; company brief from job and company data only (no hallucinated facts: state unknowns).
**Done when:** fixture job produces categorized questions and a brief.

### P5.2 Mock interview
Text mock interview: sessions/turns, interviewer persona per role, per-answer rubric feedback (structure, relevance, specificity, STAR), session summary. Credits charged per session.
**Done when:** full session works in UI; feedback schema validated.

### P5.3 Chrome extension autofill
`extension/` MV3 TypeScript: login via core-api, fetch profile/application pack, detect Greenhouse/Lever/Ashby/Workday forms, fill fields and upload CV, highlight filled fields. Never clicks submit. Log an application event when the user confirms.
**Done when:** fills saved sample Greenhouse and Lever forms in a Playwright extension test.

---

## Phase 6 — Billing, hardening, launch

### P6.1 Plans & payments
Plans (Free/Pro) with monthly credits; Paystack and Stripe checkout + webhooks (signature verified, idempotent); subscription lifecycle; credit top-ups; billing page.
**Done when:** webhook tests cover success, renewal, failure, cancellation, duplicate delivery.

### P6.2 Security hardening
Review against OWASP ASVS L2 checklist: headers, CORS, CSRF on cookie endpoints, rate limits on all expensive endpoints, upload scanning hook, ownership tests for every endpoint, secrets audit, dependency audit. Fix findings; write a report in `docs/security-review.md`.
**Done when:** report has no open high/critical items.

### P6.3 Observability
OpenTelemetry across web/core-api/ai-service, Prometheus metrics (PLAN.md §11), Grafana dashboards as code, Sentry, structured logs with trace IDs and PII redaction.
**Done when:** one request is traceable end to end locally.

### P6.4 Compliance & account data
Privacy policy/terms pages, AI-processing consent at signup, data export (JSON + files zip), verify deletion handler in every module, retention jobs (raw postings 30 days, etc.).
**Done when:** deletion test asserts no user rows/files remain in any module.

### P6.5 Production deployment
Production compose/config for chosen host, managed Postgres (pgvector) + Redis + R2, Vercel for web, GitHub Actions deploy (staging on main, prod on tag), backups + restore runbook, load test (k6) at target concurrency.
**Done when:** staging deploy from CI works; restore rehearsed; load test report committed.
