# Data inventory and retention

What personal data JobFinder stores, where, who receives it, how long it is kept and how it is erased or exported. It is
the factual base of the privacy policy template (`apps/web/src/features/legal`). Keep it in step with the code:
`AccountDeletionCompletenessTests` fails when a table with user data is added without an export decision.

## Stores

| Store | Holds | Erased at account deletion by |
|---|---|---|
| Postgres | everything in the table below | row deletion and FK cascades from `users` (one transaction) |
| Object storage `resumes/<user id>/` | uploaded CV files | `ProfileDeletionHandler` (by prefix, also catches orphans) |
| Object storage `renders/<user id>/` | generated PDF and DOCX | `RenderingDeletionHandler` (by prefix) |
| Redis | rate-limit counters keyed by user id or IP, minutes to an hour | `RateLimiter.forgetSubject` (best effort; they expire) |
| RabbitMQ | parse and embedding messages: ids only, no personal data | messages for a deleted user are dropped by the consumer |
| Vectors (pgvector columns on `resume_versions`) | numbers derived from CV text | row deletion |
| ai-service | stateless; no database or disk store | n/a |
| Logs and Sentry | scrubbed of emails, phones, tokens and CV text (ADR 0039) | not user-addressable; see retention of the log platform |

## Tables with user data

| Table(s) | Module | Content | Export file | On deletion |
|---|---|---|---|---|
| `users`, `oauth_accounts` | identity | email, password hash or Google id, consent version/time | `account/*` | deleted |
| `refresh_tokens`, `email_tokens` | identity | hashed session and link tokens | not exported (secrets) | deleted |
| `profiles`, `preferences` | profile | name, headline, location, phone, links, experience, job preferences | `profile/*` | deleted |
| `resumes`, `resume_versions` | profile | CV metadata, parsed content, vectors; the files | `profile/*`, `profile/cv-files/*` | deleted, files deleted |
| `user_job_actions` | jobs | saved, hidden, viewed, applied | `jobs/*` | deleted |
| `match_scores` | matching | score and explanation per job | `matching/*` | deleted |
| `generated_documents`, `application_packs` | documents | tailored CVs, letters, answers, packs | `documents/*` | deleted |
| `rendered_files` | rendering | index of generated files; the files | `generated-files/*` | deleted, files deleted |
| `applications`, `application_events`, `reminders` | applications | tracker, notes, history, reminders | `applications/*` | deleted |
| `interview_prep`, `interview_questions`, `company_briefs`, `interview_sessions`, `interview_turns` | interview | prep and mock interview answers and feedback | `interview/*` | deleted |
| `saved_searches`, `notification_preferences`, `notification_log` | notifications | searches, email settings, send log | `notifications/*` | deleted |
| `subscriptions`, `credit_ledger` | billing | plan, provider refs, credit history | `billing/*` | deleted (see ADR 0040 open point); remote subscription cancelled |
| `ai_calls` | billing | feature, model, tokens, cost; no content | `billing/ai-calls.json` | kept with the user removed |
| `webhook_events`, `remote_cancellations` | billing | provider ids only | n/a | n/a (no user column) |

## Retention (enforced by `compliance` tasks; defaults, `app.retention.*`)

| Data | Period |
|---|---|
| User-created data | until deleted by the user |
| Unverified accounts | 30 days |
| Expired tokens | 7 days after expiry |
| Raw job postings (not personal) | 30 days |
| Webhook delivery ids | 180 days |
| Email send log | 365 days |
| Cached rendered files | 90 days |
| Backups | [BACKUP RETENTION PERIOD] (set in P6.5) |

## Recipients (subprocessors actually integrated)

| Recipient | Purpose | Personal data sent | Condition |
|---|---|---|---|
| Anthropic | LLM: parsing, matching, tailoring, letters, interview | CV content, profile, preferences, job text, user-typed text | AI consent |
| Voyage AI | embeddings | CV content | AI consent |
| Stripe, Paystack | payments | email, plan, refs (card data on their pages) | when paying |
| Google | sign-in | ID token verification | when chosen |
| Sentry | error reports (scrubbed) | error details, trace ids | only if a DSN is set |
| [EMAIL DELIVERY PROVIDER] | transactional and notification email | email address, message | production |
| [HOSTING PROVIDER] | hosting, database, storage | all of the above | production |

## Rights and how they are met

| Right | How |
|---|---|
| Access, portability | `GET /me/export` (Settings, Privacy and data) |
| Rectification | edit profile, preferences and parsed CV in the app |
| Erasure | `DELETE /me` (same page) |
| Withdraw consent | `DELETE /me/consent/ai`; email settings; stops AI calls at once |
| Restriction, objection | by email to the data protection contact; AI consent withdrawal covers consent-based processing |
| Complaint | regulator (Nigeria Data Protection Commission; EU/UK authorities) |
