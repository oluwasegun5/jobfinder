# JobFinder — Master Build Plan

AI-enabled job finding platform: aggregate jobs from many sources, match them to a candidate, tailor resumes and cover letters, assist with applying, track applications, and prepare for interviews.

This file is the source of truth. Claude Code should read it (and `CLAUDE.md`) before every task. Work is executed through the prompts in `PROMPTS.md`, in order.

---

## 1. Key decisions (read first)

| # | Decision | Why |
|---|---|---|
| D1 | **Modular monolith (Spring Boot) + one Python AI service**, not 8 microservices on day one | One developer + Claude Code. Microservices multiply infra, auth, deployment and debugging cost before there are users. Modules have hard boundaries (Spring Modulith) so any of them can be extracted later. |
| D2 | **PostgreSQL + pgvector** for data *and* vector search | One database to run, back up and secure. Move to OpenSearch/dedicated vector DB only when metrics say so. |
| D3 | **Official/public APIs first, scraping last** | ATS public board APIs (Greenhouse, Lever, Ashby, Workable, SmartRecruiters, Recruitee) and aggregator APIs (Adzuna, JSearch, Jooble) are stable and legal. Scraping is brittle and often breaches ToS. |
| D4 | **No LinkedIn/Indeed scraping or automated login** | Both prohibit it and actively ban accounts; it is a legal risk for a product. Their listings are reachable indirectly via aggregator APIs (e.g. JSearch / Google for Jobs). |
| D5 | **"Assisted apply" by default, auto-submit only where permitted** | Fully automated submission on third-party sites breaks ToS, hits CAPTCHAs, and sends low-quality applications that hurt users. Default: prefilled application + user confirms. True auto-submit only through channels that allow it. |
| D6 | **Human-in-the-loop for every AI document** | Tailored resumes and cover letters are shown as a diff and must be approved before use. The AI may rephrase and reorder real experience; it must never invent it. |
| D7 | **Provider-agnostic LLM layer** | All LLM calls go through the AI service behind one interface, so models/providers can be swapped and costs tracked centrally. |
| D8 | **Usage metering from day one** | Every AI call is recorded against a user's credit ledger. LLM cost is the main variable cost of the product. |

---

## 2. Product scope

### Personas
- **Job seeker (primary)** — uploads a CV, sets preferences, gets matches, applies, tracks, prepares.
- **Admin** — manages sources, monitors ingestion, handles abuse, views metrics.
- *(Later)* **Recruiter/employer** — posts jobs, sees matched candidates who opted in.

### Feature list by module
1. **Accounts** — email/password + Google sign-in, email verification, password reset, account deletion (full data purge).
2. **Profile & CV** — CV upload (PDF/DOCX), AI parsing into a structured profile (experience, education, skills, links), manual editing, multiple CV versions, preferences (roles, locations, remote/hybrid/onsite, salary floor, seniority, visa sponsorship need, industries to avoid).
3. **Job aggregation** — scheduled ingestion from all sources, normalization, deduplication, expiry detection, company records.
4. **Search** — keyword + filters + semantic ("jobs like this one"), saved searches.
5. **Matching** — per-user ranked feed with a 0–100 score and a short "why you match / gaps" explanation.
6. **Resume tailoring** — per-job tailored CV (diff view, approve, export ATS-friendly PDF/DOCX), ATS keyword coverage check.
7. **Cover letters** — per-job generation with tone options, editable.
8. **Apply** — assisted apply (prefilled answers, one-click open + copy), browser extension autofill (Phase 5), auto-submit only via permitted channels.
9. **Application tracker** — kanban: Saved → Applied → Screening → Interview → Offer → Rejected/Withdrawn, notes, reminders, follow-up email drafts.
10. **Interview prep** — likely questions from the JD, company brief, text mock interview with scored feedback; voice mode later.
11. **Notifications** — daily/weekly match digests (email), new high-score match alerts, follow-up reminders.
12. **Billing** — free tier + paid plans with AI credits. Paystack (Nigeria/Africa) + Stripe (international).
13. **Admin console** — source health, ingestion stats, user management, AI cost dashboard.

### Out of scope for v1
Recruiter side, mobile apps (the web app must be responsive), voice interviews, salary negotiation coaching.

---

## 3. Architecture

```
                    ┌────────────────────────┐
                    │  Next.js web app       │  (TypeScript, App Router)
                    └───────────┬────────────┘
                                │ HTTPS (JWT)
                    ┌───────────▼────────────┐
                    │  core-api              │  Spring Boot, Java 21
                    │  (modular monolith)    │  Spring Modulith modules:
                    │                        │  identity · profile · jobs ·
                    │                        │  ingestion · matching · documents ·
                    │                        │  applications · interview ·
                    │                        │  notifications · billing · admin
                    └───┬─────────┬──────┬───┘
            REST (sync) │         │      │ RabbitMQ (async)
                ┌───────▼───┐     │  ┌───▼──────────────────┐
                │ ai-service│◄────┼──│ ai-service workers   │
                │ FastAPI   │     │  │ (embeddings, parsing,│
                └─────┬─────┘     │  │  batch scoring)      │
                      │           │  └──────────────────────┘
          LLM + embedding APIs    │
                            ┌─────▼──────┐  ┌───────┐  ┌──────────────┐
                            │ PostgreSQL │  │ Redis │  │ Object store │
                            │ + pgvector │  │       │  │ (S3 / R2)    │
                            └────────────┘  └───────┘  └──────────────┘
                    ┌────────────────────────┐
                    │ scraper-worker         │  Playwright (Python) — only for
                    │ (Phase 3+, optional)   │  sources with no API and permissive ToS
                    └────────────────────────┘
```

### Components
| Component | Tech | Responsibility |
|---|---|---|
| `apps/web` | Next.js, TypeScript, Tailwind, shadcn/ui, TanStack Query | All user and admin UI |
| `services/core-api` | Java 21, Spring Boot (latest stable), Spring Modulith, Spring Security, Spring Data JPA, Flyway | Business logic, auth, REST API, scheduling, ingestion orchestration |
| `services/ai-service` | Python 3.12, FastAPI, Pydantic, `anthropic` SDK, embeddings client | CV parsing, embeddings, match scoring/explanations, tailoring, cover letters, interview prep |
| `services/scraper-worker` | Python, Playwright | Fallback scraping (Phase 3+) |
| `extension/` | Chrome extension (MV3, TypeScript) | Autofill on application forms (Phase 5) |
| PostgreSQL 16 + pgvector | | Primary store + vector search |
| Redis | | Cache, rate limiting, refresh-token denylist, idempotency keys |
| RabbitMQ | | Async jobs between core-api and ai-service |
| S3-compatible storage (Cloudflare R2) | | CVs and generated documents (private, pre-signed URLs) |

### Communication rules
- Web → core-api only. The web app never calls ai-service directly.
- core-api → ai-service: synchronous REST for interactive requests (tailor this CV now), RabbitMQ for batch work (embed 5,000 new jobs).
- ai-service is internal only (no public ingress), authenticated with a shared service token.
- Modules inside core-api talk through public module APIs and Spring application events, never through each other's repositories.

---

## 4. Repository layout

```
jobfinder/
├── apps/
│   └── web/                      # Next.js
├── services/
│   ├── core-api/                 # Spring Boot modular monolith
│   │   └── src/main/java/.../
│   │       ├── identity/
│   │       ├── profile/
│   │       ├── jobs/
│   │       ├── ingestion/
│   │       │   └── sources/      # one adapter per source
│   │       ├── matching/
│   │       ├── documents/
│   │       ├── applications/
│   │       ├── interview/
│   │       ├── notifications/
│   │       ├── billing/
│   │       ├── admin/
│   │       └── shared/           # errors, pagination, security utils
│   ├── ai-service/               # FastAPI
│   │   └── app/
│   │       ├── llm/              # provider interface + implementations
│   │       ├── prompts/          # versioned prompt templates
│   │       ├── parsing/
│   │       ├── matching/
│   │       ├── tailoring/
│   │       ├── interview/
│   │       └── workers/
│   └── scraper-worker/           # Phase 3+
├── extension/                    # Phase 5
├── packages/
│   └── api-contract/             # OpenAPI spec + generated TS client
├── infra/
│   ├── docker-compose.yml        # full local stack
│   ├── docker/                   # Dockerfiles
│   └── deploy/                   # production config (Phase 6)
├── docs/
│   ├── adr/                      # architecture decision records
│   └── runbooks/
├── .github/workflows/
├── PLAN.md
├── CLAUDE.md
└── PROMPTS.md
```

---

## 5. Data model (core tables)

All tables: `id UUID PK`, `created_at`, `updated_at`. Soft delete only where noted.

**identity**
- `users` — email (unique, citext), password_hash (nullable for OAuth), email_verified_at, role (USER/ADMIN), status, deleted_at
- `oauth_accounts` — user_id, provider, provider_user_id
- `refresh_tokens` — user_id, token_hash, family_id, expires_at, revoked_at (rotation + reuse detection)

**profile**
- `profiles` — user_id, full_name, headline, location, phone, links (jsonb), years_experience, seniority
- `preferences` — user_id, target_titles[], locations[], work_modes[], min_salary, currency, needs_sponsorship, excluded_companies[], excluded_industries[]
- `resumes` — user_id, label, file_key, file_type, is_primary, parse_status
- `resume_versions` — resume_id, structured (jsonb: experience, education, skills, projects), source (UPLOAD/EDIT/TAILORED), embedding vector

**jobs / ingestion**
- `sources` — code (GREENHOUSE, ADZUNA…), kind (ATS/AGGREGATOR/SCRAPE), enabled, config (jsonb), last_run_at, health
- `source_targets` — source_id, identifier (e.g. Greenhouse board token), company_id, enabled
- `ingestion_runs` — source_id, started_at, finished_at, fetched, created, updated, expired, errors
- `raw_job_postings` — source_id, external_id, payload (jsonb), fetched_at (kept 30 days for reprocessing)
- `companies` — name, normalized_name, domain, logo_url, size, industry
- `jobs` — company_id, title, normalized_title, description_html, description_text, location_raw, city, country, work_mode, employment_type, seniority, salary_min, salary_max, salary_currency, salary_period, apply_url, apply_channel (EXTERNAL/ATS_API/EMAIL), posted_at, expires_at, status (ACTIVE/EXPIRED), fingerprint (unique), skills[], embedding vector, search tsvector
- `job_sources` — job_id, source_id, external_id, url (a job can come from several sources)

**matching**
- `match_scores` — user_id, job_id, resume_version_id, vector_score, llm_score, explanation (jsonb: strengths, gaps), model, prompt_version, computed_at
- `user_job_actions` — user_id, job_id, action (VIEWED/SAVED/HIDDEN/APPLIED) — feedback signal for ranking

**documents**
- `generated_documents` — user_id, job_id, type (TAILORED_RESUME/COVER_LETTER), base_resume_version_id, content (jsonb), status (DRAFT/APPROVED), file_key, model, prompt_version

**applications**
- `applications` — user_id, job_id (nullable for manual entries), company, title, status, applied_at, channel, resume_document_id, cover_letter_document_id, notes
- `application_events` — application_id, type, from_status, to_status, note, occurred_at
- `reminders` — user_id, application_id, due_at, sent_at

**interview**
- `interview_sessions` — user_id, job_id, application_id, mode (QUESTIONS/MOCK), status
- `interview_turns` — session_id, role, content, feedback (jsonb: score, strengths, improvements)

**notifications**
- `saved_searches` — user_id, query, filters (jsonb), alert_frequency
- `notifications` — user_id, type, payload, channel, sent_at, read_at

**billing**
- `plans`, `subscriptions` — user_id, plan_id, provider (PAYSTACK/STRIPE), provider_ref, status, period_end
- `credit_ledger` — user_id, delta, reason, ai_call_id, balance_after
- `ai_calls` — user_id, feature, provider, model, input_tokens, output_tokens, cost_usd, latency_ms, prompt_version, status

Indexes: HNSW on `jobs.embedding` and `resume_versions.embedding`; GIN on `jobs.search`; btree on `(status, posted_at)`, `(country, work_mode)`, `jobs.fingerprint`.

---

## 6. Job ingestion design

### Source tiers
| Tier | Sources | Method | Notes |
|---|---|---|---|
| 1 — ATS public boards | Greenhouse (`boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true`), Lever (`api.lever.co/v0/postings/{company}?mode=json`), Ashby (`api.ashbyhq.com/posting-api/job-board/{company}?includeCompensation=true`), Workable, SmartRecruiters, Recruitee | Public JSON, no key | Highest quality; direct from employer. Need a curated list of company board tokens (seed list + discovery job). |
| 2 — Aggregator APIs | Adzuna (free key), JSearch via RapidAPI (Google for Jobs: covers LinkedIn/Indeed/Glassdoor listings), Jooble, Remotive, Arbeitnow, RemoteOK, USAJobs, Reed (UK) | API key | Broad coverage; watch quotas and each API's display/attribution terms. |
| 3 — RSS / feeds | Boards exposing RSS/XML | HTTP | Cheap wins. |
| 4 — Scraping | Local boards with no API (e.g. Nigerian/African boards) **only where ToS/robots.txt permit** | Playwright worker | Isolated service, strict per-domain rate limits, identifies itself, easy to disable per source. |

Verify each source's current terms and endpoint shape when implementing its adapter; record findings in `docs/adr/`.

### Pipeline
1. **Schedule** — per source cron (Tier 1 every 6h, Tier 2 per quota), with jitter. ShedLock so only one instance runs a source.
2. **Fetch** — adapter implements `JobSourceAdapter { fetch(target, since) → Stream<RawPosting> }`. Retries with backoff, per-source rate limiter, circuit breaker (Resilience4j).
3. **Store raw** — `raw_job_postings` (enables reprocessing when normalization improves).
4. **Normalize** — map to `jobs` schema: clean HTML → text, parse location (city/country/remote), infer work mode, employment type, seniority from title, normalize salary to min/max/currency/period. Deterministic rules first; LLM extraction only for fields rules can't get, batched with a cheap model.
5. **Deduplicate** — fingerprint = hash(normalized company + normalized title + normalized location). Same fingerprint → merge, add `job_sources` row. Near-duplicates (embedding cosine > 0.97, same company) flagged and merged in a nightly pass.
6. **Enrich** — skills extraction, company upsert, embedding (async via RabbitMQ → ai-service).
7. **Expire** — job missing from its source for 2 consecutive runs → `EXPIRED`; aggregator jobs older than 45 days without refresh → `EXPIRED`. Apply links checked lazily when a user opens a job.
8. **Observe** — `ingestion_runs` metrics, alert when a source returns 0 jobs or error rate > 20%.

---

## 7. AI design

### Model routing (configurable, never hard-coded in business logic)
| Task | Model class | Example |
|---|---|---|
| CV parsing, field extraction, bulk scoring | Fast/cheap | `claude-haiku-4-5` |
| Tailoring, cover letters, interview feedback | Strong | `claude-sonnet-5` |
| Embeddings | Embedding model | Voyage AI or OpenAI embeddings (pin model + dimension in config; changing it requires a re-embed job) |

### CV parsing
PDF/DOCX → text (pdfplumber / python-docx; OCR fallback) → LLM with a strict JSON schema (Pydantic-validated, one retry on validation failure) → user reviews and corrects the parsed profile.

### Matching (two-stage)
1. **Hard filters (SQL)** — active jobs, location/work-mode/sponsorship/excluded companies, seniority band.
2. **Recall** — pgvector cosine similarity between primary resume embedding and job embeddings → top 300. Blend with keyword/skill overlap and recency.
3. **Rerank** — LLM scores top 30 against the full profile: 0–100 score + strengths + gaps as JSON. Cached in `match_scores` keyed by (resume_version, job, prompt_version). Recomputed only when the resume or job changes.
4. **Feedback** — saves/hides/applies adjust future ranking (start with simple weighting; learn-to-rank later).

Daily batch computes feeds for active users; on-demand scoring for a job a user opens.

### Tailoring guardrails
- Input: approved base resume version + job description. Output: structured resume JSON of the same shape.
- Allowed: reorder, rephrase, emphasize, select relevant bullets, adjust summary, surface matching skills the user **already has**.
- Forbidden: new employers, titles, dates, degrees, certifications, metrics or skills not present in the source. Enforced by a post-generation **fact check** step that compares entities against the source and flags anything new.
- Shown as a diff; user approves before export. ATS-friendly PDF/DOCX rendering from templates (no tables/columns in the ATS template).

### Prompt injection defense
Job descriptions and scraped pages are **untrusted input**. They are wrapped in clearly delimited data blocks, the system prompt states they contain no instructions, outputs are schema-validated, and no AI step has tools or side effects (LLM output never triggers an action without code-level validation).

### Prompt management
Prompts live as versioned files in `ai-service/app/prompts/`. Every stored AI output records `model` and `prompt_version`. A small eval set (20–50 real CV/JD pairs, anonymized) runs in CI for parsing and matching changes.

### Cost control
Credit ledger per user; hard per-user daily caps; caching of all deterministic-input calls; cheap model for bulk; batch API where latency doesn't matter; admin dashboard of cost per feature per day.

---

## 8. Apply strategy

| Mode | When | How |
|---|---|---|
| **Assisted apply** (default) | All jobs | Application pack: tailored CV, cover letter, pre-written answers to common questions (work authorization, notice period, salary expectation, "why this company"). Open the apply URL; tracker entry created when the user confirms they applied. |
| **Extension autofill** (Phase 5) | Greenhouse/Lever/Ashby/Workday forms in the user's own browser | Chrome extension fills fields from the profile; **the user reviews and clicks submit**. Runs in the user's session, not ours. |
| **Direct submit** | Only channels that permit it (e.g. employer-provided application APIs, email applications where the posting asks for email) | Explicit per-job user confirmation. Never bulk "apply to 100 jobs". |

Unattended bulk auto-submission on third-party sites is intentionally excluded (ToS violations, CAPTCHAs, account bans, and spam applications that damage users' reputations). Revisit only for specific partners who allow it.

---

## 9. Security, privacy, compliance

- Auth: short-lived JWT access tokens (15 min) + rotating refresh tokens (httpOnly, Secure, SameSite cookie) with reuse detection. BCrypt/Argon2 password hashing. Rate-limited login, signup, reset.
- Authorization: every query scoped by `user_id`; ownership checks tested per endpoint. Admin endpoints role-gated.
- PII: CVs are sensitive. Private bucket, pre-signed URLs (5 min), encryption at rest, never logged. Structured logs redact emails/phones.
- Data protection: comply with the **Nigeria Data Protection Act 2023** and **GDPR** (EU users): privacy policy, consent for AI processing, data export, full account deletion (DB + storage + vector rows), data retention policy. Choose LLM providers/settings that do not train on submitted data.
- Secrets in environment/secret manager only; never in the repo. Dependabot + CodeQL + OWASP dependency check in CI.
- Input validation on all DTOs; file upload limits (type sniffing, 5 MB, virus scan in production).
- Scraping etiquette: respect robots.txt and ToS, identify the bot, per-domain rate limits.

---

## 10. Quality and testing

- core-api: JUnit 5, Testcontainers (Postgres+pgvector, Redis, RabbitMQ), `ApplicationModules.verify()` for module boundaries, WireMock for every source adapter (recorded fixtures).
- ai-service: pytest, provider mocked in unit tests, eval suite for prompts (runs on prompt changes, not every commit).
- web: Vitest + Testing Library, Playwright E2E for core flows (signup → upload CV → see matches → tailor → track).
- Contract: OpenAPI spec generated from core-api, TS client generated for web; CI fails if the client is stale.
- Coverage target: 80% on domain/service layers; no coverage theatre on DTOs.
- Every PR: lint, tests, build, Docker image build.

---

## 11. Observability & operations

- OpenTelemetry traces across web → core-api → ai-service; Prometheus metrics; Grafana dashboards; Sentry for errors (web + both services).
- Key metrics: ingestion jobs/source/day, source error rate, active jobs, match latency, AI cost/day/feature, credits consumed, signup→first-match conversion, apply rate.
- Structured JSON logs with request/trace IDs.
- Backups: daily Postgres snapshots + PITR; tested restore runbook.

---

## 12. Infrastructure & deployment

| Stage | Setup |
|---|---|
| Local | `docker compose up` runs Postgres+pgvector, Redis, RabbitMQ, S3Mock (S3-compatible object storage), Mailpit, core-api, ai-service, web |
| Staging/Prod v1 | Managed Postgres (with pgvector), managed Redis, CloudAMQP or self-hosted RabbitMQ, Cloudflare R2; services as containers on Fly.io / Railway / Render or a single VM with Docker Compose + Caddy. Web on Vercel. |
| Scale-up | Kubernetes only when traffic or team size justifies it |

CI/CD: GitHub Actions — PR checks; merge to `main` deploys to staging; tagged release deploys to production. Flyway migrations run on deploy (backward-compatible migrations only).

---

## 13. Delivery phases

Each phase ends with a working, deployable increment. Acceptance criteria must pass before moving on.

### Phase 0 — Foundations (week 1)
Monorepo, docker-compose stack, core-api + ai-service + web skeletons, health checks, CI pipeline, OpenAPI → TS client generation, ADRs for D1–D8.
**Done when:** `docker compose up` brings up everything healthy; CI green on an empty feature PR.

### Phase 1 — Accounts & profile (weeks 2–3)
Auth (email + Google), verification, reset, CV upload to object storage, CV parsing via ai-service, profile editing, preferences, account deletion.
**Done when:** a new user can sign up, upload a CV, see a correctly parsed editable profile, set preferences, and delete their account completely.

### Phase 2 — Job aggregation & search (weeks 3–5)
Ingestion framework, Greenhouse/Lever/Ashby/Workable/SmartRecruiters adapters, Adzuna/JSearch/Remotive adapters, normalization, dedup, expiry, embeddings, search API (keyword + filters + semantic), job list/detail UI, saved jobs, admin source dashboard.
**Done when:** ≥ 20k active deduplicated jobs from ≥ 6 sources refresh automatically; search returns in < 300 ms p95; a failing source raises an alert without affecting others.

### Phase 3 — AI matching (weeks 5–7)
Two-stage matching, match explanations, personalized feed, "why this match" UI, feedback signals, daily digest email, AI call logging + credit ledger.
**Done when:** each user with a profile gets a ranked feed with explanations; re-ranking is cached; AI cost per active user/day is visible in admin.

### Phase 4 — Documents & tracker (weeks 7–9)
Resume tailoring with fact-check + diff + approval, ATS-friendly export, cover letters, application pack, assisted apply, kanban tracker, reminders, follow-up drafts.
**Done when:** a user can go from a job to an approved tailored CV + cover letter + tracker entry in under 3 minutes, and the fact-check catches injected fake experience in tests.

### Phase 5 — Interview prep & extension (weeks 9–11)
Question generation, company brief (from job + company data), text mock interview with rubric feedback, Chrome extension autofill for major ATS forms.
**Done when:** a mock interview produces per-answer feedback; the extension fills a Greenhouse and a Lever form from the profile, leaving submit to the user.

### Phase 6 — Billing, hardening, launch (weeks 11–13)
Plans + credits, Paystack + Stripe webhooks, rate limits, security review, load test, observability dashboards, backups, legal pages, production deploy, onboarding flow.
**Done when:** a user can subscribe and credits apply; load test at target concurrency passes; restore from backup verified; privacy policy + terms live.

### Phase 7 — Post-launch
Scraper worker for approved local sources, recruiter side, voice mock interviews, learn-to-rank from feedback, mobile apps, salary insights.

---

## 14. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Source APIs change or disappear | Adapter per source, WireMock fixtures, health alerts, many sources so none is critical |
| Legal/ToS exposure | D3–D5; per-source terms recorded in ADRs; kill switch per source |
| LLM cost overruns | Credits, caps, caching, cheap-model routing, cost dashboard |
| AI fabricates CV content | Guardrails + fact-check + mandatory human approval |
| Prompt injection via job descriptions | Untrusted-data handling, schema validation, no tool access |
| Poor match quality | Eval set, explanation UI, user feedback loop |
| Data breach of CVs | Encryption, private storage, least privilege, audit logging, deletion |
| Scope creep | Phase gates; nothing from a later phase until current acceptance criteria pass |

---

## 15. Working with Claude Code

- One prompt from `PROMPTS.md` per session/branch. Don't combine phases.
- Start each session with: "Read PLAN.md and CLAUDE.md, then do task X."
- Review the Flyway migration and API contract of every PR yourself — everything downstream depends on them.
- After each phase, update this file's "Status" section.

## Status
- [x] Phase 0
- [x] Phase 1
- [x] Phase 2
- [x] Phase 3
- [x] Phase 4
- [x] Phase 5
- [ ] Phase 6
