# 0040. Compliance and account data: consent, export, deletion, retention, legal templates

## Status
Accepted

## Context
`PLAN.md` section 9 requires compliance with the Nigeria Data Protection Act 2023 and the GDPR: a privacy policy, consent
for AI processing, data export, full account deletion (database, storage, vector rows) and a retention policy. `PROMPTS.md`
P6.4 adds terms pages, a "verify the deletion handler in every module" check and retention jobs, and its acceptance test
is that a deletion test asserts no user rows or files remain in any module. Account deletion existed since Phase 1
(`UserDeletionRequested`, one listener per module, one transaction); this task proves it for the whole schema and adds the
rest. Migration `V35` is the only schema change. The legal texts are templates (see "Legal templates").

## Decision

### Consent to AI processing
- Stored on the user row: `ai_consent_version`, `ai_consent_at` (V35, nullable). Null means no consent. Withdrawing sets both
  to null; the consent record goes with the account.
- Signup requires `aiProcessingConsent: true` (400 otherwise). Accounts created with Google, and every account that existed
  before V35, start **without** consent: consent that was never asked for is not presumed. The web shows a consent screen
  (`ConsentGate`) on the signed-in pages until it is given; `/settings/privacy` stays reachable so it can be given, data
  downloaded or the account deleted instead.
- Enforcement is at the one place every user-attributed AI call passes: billing's `AiUsageGate`
  (`requireAllowance` throws `AiConsentRequiredException`, 403 `ai_consent_required`; `status` returns
  `CONSENT_REQUIRED`). Because it is an `AiAllowanceException`, existing callers degrade as they do for an empty balance:
  the resume parse worker fails the parse with `ai_consent_required` (retryable by the user after consenting), matching
  serves stage-2 scores with `FallbackReason.CONSENT_REQUIRED`, the embedding inputs endpoint withholds the resume text
  (`skipped: CONSENT_REQUIRED`) so it never reaches the embedding provider, and the interactive endpoints answer 403. Batch
  jobs pass through the same gate, so withdrawal also stops them.
- CV upload is refused before anything is stored without consent (uploading means a model will read it).
- The identity module exposes `AiConsent` (public); billing and profile depend on that interface only.

### Data export (`GET /me/export`)
- Rate class `DATA_EXPORT` (3 per hour). The response is a zip: `<module>/<name>.json` per module, the original CV files and
  rendered PDF/DOCX files, `manifest.json` (every file, and anything that exists but could not be included) and a README.
  Built in a temp file, so a failing exporter yields an error response, never a truncated archive.
- SPI: `identity.UserDataExporter` + `UserDataBundle`, the mirror of `UserDeletionRequested`; every module that stores user data
  implements one (10 exporters). Each receives only the id of the caller (taken from the token, never the request) and selects
  by it. Secrets and internal columns are left out (password hash, token hashes, embeddings, storage keys, content hashes).
- `AccountDeletionCompletenessTests` fails if a user-owned table has neither an export file nor an entry in its "not exported,
  and why" list (only `refresh_tokens` and `email_tokens`).

### Account deletion
- Mechanism unchanged: `UserDeletionRequested` published inside the deleting transaction, every module deletes its rows (or
  the FK cascades do) and its files by prefix; a failing listener rolls the whole thing back. Added: identity also erases the
  user's Redis rate-limit keys (`rl:*:<user id>`), best effort (counters only, they expire anyway).
- **Proof for every module at once**: `AccountDeletionCompletenessTests` reads the Postgres catalog and fails when (1) any
  foreign key into user-owned data does not cascade, other than the three documented ones (`ai_calls.user_id` SET NULL,
  `credit_ledger.ai_call_id`, `notification_log.saved_search_id`), (2) any `user_id`-like or `email` column exists without a
  foreign key to `users`, or (3) a new user-owned table has no export decision; and it deletes a user with data in about 14
  tables, S3 (`resumes/`, `renders/`) and Redis through the API, asserting nothing is left and a bystander is untouched.
- Late messages: a queued parse or embedding message for a deleted user finds no resume and is dropped (tests).
- **Kept, anonymised**: `ai_calls` (feature, model, token counts, cost; no content) lose their user. They are the business's
  cost history. `remote_cancellations` and `webhook_events` hold provider ids only.
- **Open point (decision recorded, revisit with an accountant):** the user's `credit_ledger` and `subscriptions` rows are
  erased (ADR 0036). JobFinder stores no invoices, receipts or tax documents (Stripe and Paystack do, and are the
  records of payment), and the ledger is credit accounting, not a financial record. If an accountant or tax rule requires
  JobFinder itself to keep payment history, the alternative is to anonymise instead of erase: replace `user_id` with a
  tombstone user, keep amount, currency, provider reference and time, and drop everything else. It is a change to
  `BillingDeletionHandler` and to the FK on the ledger, with a migration; nothing else depends on it.
- Backups are outside the application: re-applying deletions after restoring a backup is NOT implemented. P6.5 must
  state the backup retention period, which the privacy template leaves as a placeholder.

### Retention (`compliance` module)
`RetentionTask` (public SPI) implemented per module; `RetentionRunner` runs them daily under a ShedLock lock
(`app.retention.*`, off in tests), one task failing never stops the others, metrics `retention_purged_total{task}` and
`retention_failures_total{task}`.

| Task | Rule | Default |
|---|---|---|
| `raw-postings` | `raw_job_postings.fetched_at` older than | 30 days |
| `expired-tokens` | refresh and email tokens expired longer ago than | 7 days |
| `unverified-accounts` | never-verified `USER` accounts older than; deleted through `AuthService.deleteAccount`, so every module purges | 30 days |
| `webhook-events` | payment-webhook ids older than (providers retry for days) | 180 days |
| `notification-log` | sent/failed email log rows older than (PENDING kept) | 365 days |
| `rendered-files` | cached PDF/DOCX (S3 object first, then the row) older than | 90 days |

Not time-limited: everything the user created lives until they delete it or the account. **Undecided**: deleting accounts that
were inactive for a long period (would need a warning email flow and a policy on how long); not built. `ai_calls` are not
pruned (the ledger references them).

### Legal templates (web)
`/privacy`, `/terms`, `/cookies`, `/subprocessors`, public, each headed "Template: not legal advice" and written from what the
code does: the data in `docs/compliance/data-inventory.md`, the retention table, the subprocessors actually integrated
(Anthropic, Voyage AI, Stripe, Paystack, Google sign-in, Sentry when a DSN is set; email and hosting providers are
placeholders because the deployment (P6.5) is not chosen). Facts about the company are highlighted placeholders in
`features/legal/placeholders.ts`. No certification or compliance claim is made. Rights are described jurisdiction-neutrally
(access/portability, rectification, erasure, restriction, objection, withdrawal of consent, complaint to the regulator,
naming the Nigeria Data Protection Commission). `docs/compliance/dpa-template.md` is the data processing agreement skeleton.
Linked from the landing page, the auth pages and the app's Privacy and data page.

### Cookies
One strictly necessary cookie (`refresh_token`, httpOnly, Secure, SameSite=Strict); the access token is in memory; no
analytics, no localStorage use. Therefore a notice page, not a consent banner. Flagged for the lawyer to confirm for the
target countries; if analytics is ever added it needs consent first.

## Consequences
- Existing users must accept the AI consent once before AI features and CV upload work again (visible behaviour change).
- Every new table with user data needs: a cascade to the owner, an exporter (or an explained exclusion) and, if it is
  time-limited, a `RetentionTask`; the completeness test enforces the first two.
- Open for the owner: lawyer review of the templates and fill-in of the placeholders; the ledger-erasure question above;
  inactive-account deletion; backup retention in P6.5.
