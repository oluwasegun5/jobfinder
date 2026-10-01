# 0029. Resume tailoring with a separate fact check

## Status
Accepted

## Context
P4.1 (`PLAN.md` section 7). A user picks a job and wants their resume reshaped for it. The risk is not the writing, it
is **a resume that says something untrue under the user's name**: an employer they never worked at, a degree they do
not hold, a number the model liked the sound of. The job description is third-party text and can contain instructions
aimed at the model. The model's output is untrusted. So: the model may only reorder, rephrase, emphasise and select
from facts that are in the source resume, a **separate** step with its own code path checks that, and a human approves
the result. Everything must be testable with no real model (Segun's Anthropic account has no credit): the tests use
the deterministic fake provider, WireMock and pinned contract files.

## Decision

### ai-service: `POST /v1/tailor-resume` (strong model, prompt `tailor_resume/v1`)
Input: the structured source resume, the job (title, company, description: untrusted), options (`rewrite_summary`,
`max_bullets_per_role`), and `user_id` and `prompt_version` as for the other calls. The prompt version is a file under
`app/prompts/tailor_resume/` and is pinned by core-api config (`TAILORING_PROMPT_VERSION`, default `tailor_resume/v1`), sent with every request and stored with every draft; an
unknown version is a 400 `unknown_prompt_version`, as for matching.

The pipeline is: **scrub, one model call, finalize in code, diff in code, fact check in code.**

1. *Scrub the job text.* It is limited in length, control characters are removed, delimiter look-alikes are defused,
   and sentences that read as instructions to an AI ("ignore all previous instructions", "you are now", "add that the
   candidate...") are replaced by a fixed redaction marker. The number of redactions is returned and, if any, a
   `JOB_DESCRIPTION_INJECTION` warning is raised. The text then travels inside a block delimited by a random nonce
   the posting cannot guess, and the system prompt says everything in that block is data.
2. *The contact block never goes to the model.* The model sees the resume without `contact`; code puts the user's
   own contact back. The model cannot change a name, an email, a phone number or a link.
3. *One model call* returns strict JSON validated by Pydantic (`extra=forbid`): the tailored resume and per-change
   rationale notes. One retry on invalid JSON, then a typed error.
4. *Finalize.* Code applies the guardrails it can enforce: the user's own contact block replaces the model's, the
   headline and summary are kept as they were when `rewrite_summary` is off, entries are put back in the source's
   order (matched by identity, then by employer, then by position), and `max_bullets_per_role` is applied. Employers,
   titles, dates and degrees are deliberately **not** overwritten from the source: an entry the model changed or
   invented must reach the fact check intact so it is flagged and shown, rather than silently "repaired".
5. *Diff.* The list of changes is **computed by code** from source and result, not reported by the model: unit by
   unit (headline, summary, skills list, each experience, education, project and certification entry), each with
   `id`, `section`, `op` (`REPLACE`, `ADD`, `REMOVE`), `path`, `before`, `after` and the model's rationale for it
   (or a generic one). The model cannot hide a change by leaving it out of a list.
6. *Fact check* (below) on the result. A draft with BLOCKING flags is **still returned**, never silently dropped.

Response: `prompt_version`, `model`, `resume`, `changes`, `fact_check`, `job_text_redactions`, `usage` (one entry per
billed call, same shape as the other endpoints, feature `tailor_resume`).

### The fact check is a separate code path
`app/factcheck/` does not import the tailoring package and is also exposed on its own as `POST /v1/fact-check`
(source, candidate, optional job description), because core-api re-runs it on every edit. It has no model and no cost.
Matching is on normalised text (case, accents, punctuation, company suffixes such as Inc or Ltd, degree abbreviations
such as BSc and B.Sc., dates as year-month ranges) with a skill alias table (`k8s` is Kubernetes, `postgres` is
PostgreSQL, `JS` is JavaScript, and so on), so rephrased or reordered content is **not** flagged.

| Severity | Codes |
| --- | --- |
| BLOCKING | `NEW_EMPLOYER`, `NEW_JOB_TITLE`, `NEW_INSTITUTION`, `NEW_DEGREE`, `NEW_CERTIFICATION`, `NEW_DATE_RANGE`, `NEW_PROJECT`, `NEW_URL`, `NEW_EMAIL`, `NEW_PHONE`, `CONTACT_CHANGED`, `INJECTION_LEAKAGE` |
| WARNING | `NEW_SKILL`, `NEW_METRIC`, `NEW_NUMBER`, `NEW_YEAR`, `NEW_TERM`, `ENTRY_REMOVED`, `CHANGED_FIELD`, `JOB_TEXT_COPIED`, `JOB_DESCRIPTION_INJECTION` |

Employer, institution, degree, date range and credential are BLOCKING because they are the facts a reader checks and
a lie about them is serious. A new skill, metric or number is a WARNING because tailoring legitimately names a skill
that is in the posting and in the user's experience under another name, and a human must judge it; it is shown, not
blocking. A cheap leakage detector flags text in the output that looks like an instruction from the posting
(`INJECTION_LEAKAGE`, BLOCKING) or that copies a long run of the job text (`JOB_TEXT_COPIED`, WARNING).

An **LLM fact check is not implemented**. The deterministic layer alone catches every adversarial case in the tests;
a second model call would add cost and a new thing to trust. The endpoint is shaped so one could be added behind a
flag later (`checker_version` says which checker ran).

### Providers and the test-only misbehaving model
`LLM_PROVIDER=fake` selects the deterministic, labelled fake (model name `fake-strong`); it is never the default. Its
tailoring only reorders what is in the source. A model that misbehaves (invents an employer, obeys the job text) exists
**only in the tests**: they hand the service a provider that returns crafted JSON (`tests/fixtures/tailoring.py`). No
configuration value turns misbehaviour on, so production cannot be made to run it, and a test asserts the fake has no
such switch.

### core-api: the `documents` module
Public API: `ApprovedDocuments` (read an approved document, for the renderer of P4.2 and the cover letters of P4.3).
Everything else is `internal`. It reads through the public APIs of `profile` (the primary resume), `jobs` (the job)
and `billing` (the cap and the ledger); none of them depend on it, so there is no cycle. The module is wired to
`UserDeletionRequested` to erase its rows.

**V25 `generated_documents`**: `user_id`, `type` (`TAILORED_RESUME`, widened by P4.3), `status` (`GENERATING`, `DRAFT`,
`FACT_CHECK_FAILED`, `APPROVED`), `job_id` and `base_resume_version_id` (provenance, no foreign keys: an approved
document must outlive a deleted job or a replaced resume version; the job title and company are copied),
`source_content` (the resume snapshot the draft was made from), `content`, `changes`, `fact_check`, `prompt_version`,
`model`, `version`, timestamps.

**Changes are units with a state.** Each change is `ACCEPTED` or `REJECTED`. The document's `content` is always
*the source with the accepted changes applied* (`ChangeMaterializer`): rejecting a change puts the source's version of
that unit back, accepting it again puts the new one back, an edit replaces a unit's text and accepts it. Nothing else
about the content is stored, so there is no way for content and changes to disagree. A flag names a path in the content;
the materializer knows which change produced each unit, so every flag in a response carries a `changeId`: the one to
reject.

**Endpoints** (all authenticated, all scoped to the caller; someone else's document is a 404, never a 403):

| Endpoint | Behaviour |
| --- | --- |
| `POST /jobs/{id}/tailor` | 201 with the draft; 200 with the existing open draft for this job and resume version. 409 `resume_required`, 404 `job_not_found`, 429 `ai_daily_cap_reached` (the existing typed error, with `resetsAt` and `Retry-After`), 503 `tailoring_unavailable`. |
| `GET /documents`, `GET /documents/{id}` | The caller's documents, newest first (filters `jobId`, `status`, `limit`). |
| `PATCH /documents/{id}` | Operations `SET_STATE` (accept or reject a change) and `EDIT` (replace the text of a unit). Needs the draft `version`: an old one is a 409 `version_conflict`. Every edit re-runs the fact check on the resulting content before anything is stored; if the check is down the edit is refused (503) so a stored draft never has stale flags. |
| `POST /documents/{id}/approve` | 409 `fact_check_failed` while a BLOCKING flag remains; 409 `already_approved`; otherwise `APPROVED`. The fact check runs once more on the content that becomes final. |
| `DELETE /documents/{id}` | Drafts only: 204. Approved: 409 `document_approved`. |

**Synchronous creation with a placeholder.** One strong-model call takes some seconds, which is acceptable for a click
that is waiting for the result, and avoids a job queue and a polling client for a single call. To make a double click
and two tabs safe, a `GENERATING` row is inserted *first*; a partial unique index allows one open (`GENERATING`,
`DRAFT`, `FACT_CHECK_FAILED`) draft per user, job, resume version and type, so the second request gets that row back
(200) and **no second model call is made and no second allowance is spent**. The model is called with no database
transaction open. On an ai-service failure the placeholder is deleted (no stranded row); a request that dies leaves a
`GENERATING` row that the next request replaces once it is older than `generation-timeout` (5 minutes). To start
over, delete the draft. Another click after approval makes a new draft: an approved document is never changed.

**Billing.** A call that will really be made is checked against the daily cap (`AiUsageGate`, ADR 0025) under the new
feature `tailor_resume` before the placeholder is inserted, so a refused request leaves nothing behind. Every billed
call in ai-service's answer is recorded in the ledger as `tailor_resume`, `SUCCEEDED` when its output was used and
`FAILED` when it was discarded (an error body also lists the calls billed before the failure). The fact check has no
model: no usage, no cap, and edits are not metered. core-api validates the shape of what comes back (prompt version,
section and op names, ids, lengths, and that the BLOCKING count equals the BLOCKING flags listed: approval depends
on it) and refuses anything else.

**Immutability, in the database.** A trigger refuses every `UPDATE` of an `APPROVED` row and every `DELETE` of one
(SQLSTATE 23000, so Spring raises `DataIntegrityViolationException`), the same approach as the credit ledger. A CHECK
constraint refuses an `APPROVED` row whose stored fact check has a blocking flag, whatever wrote it. One exception,
for the law and for decency: erasing an account must erase the person's documents. `DocumentsDeletionHandler` deletes
inside a transaction that sets `app.purge_documents = 'on'` (transaction-local, so it cannot leak to another
statement); the trigger allows a `DELETE` only then. No endpoint sets it. A test proves a direct `UPDATE` or `DELETE`
is refused, that the setting does not leak out of its transaction, and that account deletion still works.

**Optimistic locking.** `version` goes up on every stored change, and `PATCH` must quote the version it saw, so two
tabs reviewing the same draft cannot overwrite each other. Rows are read `FOR UPDATE` inside the edit and approve
transactions.

### Contract pinning
ai-service's tests write the success and failure bodies to `core-api/src/test/resources/ai-service/`
(`tailor-resume-ok.json`, `fact-check-failed.json`); core-api's WireMock stubs serve those files, so the two services
cannot drift apart unnoticed. The OpenAPI contract and the TypeScript client are regenerated as described in
`packages/api-contract/README.md`.

## Consequences
- Nothing the model invents reaches an approved document unless a person approves a WARNING (a skill or number), and
  they see the warning next to the text when they do. BLOCKING facts can only be removed, never approved.
- Code restores the contact block and the entry order, but cannot decide whether a changed employer or date is
  legitimate, so the fact check is the line that stops those, not a clamp that hides them.
- The deterministic check is conservative: an unusual but true rewording of a skill can raise a WARNING. That costs the
  user a glance, not a refused draft. The alias table is data and easy to extend.
- Creation holds a request thread for the length of one model call (up to the 120 s read timeout). If tailoring ever
  becomes several calls or much slower, the placeholder row already is the job record an async version would poll.
- Rendering to PDF or DOCX (P4.2), cover letters (P4.3), the review UI (P4.4) and the application tracker (P4.5)
  are not part of this change.
