# 0031. Cover letters, screening answers and the application pack

## Status
Accepted

## Context
P4.3 (`PLAN.md` section 7, "cover letter generation, application pack: tailored CV, cover letter, answers to common
screening questions"). A tailored resume (ADR 0029) is one of three things a person sends with an application. The other
two are prose written for one job: a cover letter and the answers to the questions an application form asks (years of
experience, salary expectation, work authorisation, notice period, why this company). Prose is where a model is most
tempted to invent: an employer the candidate never had, a degree, a number. The posting is also untrusted text that is put
in front of the model, so it can carry instructions.

The rules of ADR 0029 therefore apply unchanged: nothing the model wrote is trusted, an unsupported claim blocks approval,
an approved document is immutable, and every model call is metered against the daily allowance (ADR 0020).

## Decision

### Letters and answers are documents, not a second system
`generated_documents` (V25) gains two types, `COVER_LETTER` and `SCREENING_ANSWERS`, one status, `SUPERSEDED`, and an
`options` column (tone, length, the user's notes). Everything V25 guarantees keeps applying to them because the guarantees
are on the table and not in a service: the trigger that makes an `APPROVED` row immutable, the CHECK that an approved row
has no blocking flag, the one-open-draft-per-job rule (split in two partial indexes so a regeneration can have its
`GENERATING` placeholder while the draft it replaces still exists). Review, edit, approve, delete and download use the
endpoints that already exist. A new CHECK adds the one rule the answers need: an approved set has no question left in
`NEEDS_INPUT`.

`changes` stays empty for both new types: there is no diff against a source, the text is the document. `source_content`
is still the resume snapshot the prose is checked against.

### Tone and length are options, not prompts
`tone` (`FORMAL`, `WARM`, `CONCISE`) and `length` (`SHORT`, `STANDARD`, `LONG`) are enums validated in core-api and passed
to ai-service, which maps them to wording inside its own versioned prompts (`cover_letter/v1`, `screening_answers/v1`). The
user's free-text note is length-limited, sent as data, and goes through the same fact check as the output. Generating again
with other options makes a new draft and marks the old one `SUPERSEDED` (kept, read-only, never approvable). An `APPROVED`
document is never superseded or overwritten: a regeneration writes a new draft beside it.

### The fact check covers prose, and it is a separate call
Structured tailoring is checked field by field (ADR 0029). Prose cannot be, so ai-service gets `/v1/fact-check-text`: it
extracts the checkable claims from the text (employers, titles, schools, degrees, certifications, dates, numbers) and
compares them with the resume snapshot, the job title and company, and the user's own notes. A claim with no support is a
blocking flag (`NEW_EMPLOYER`, `NEW_DEGREE`, `NEW_METRIC`...) and the document is `FACT_CHECK_FAILED`: it can be
edited and re-checked, not approved. The flags are recomputed at approval from the content about to become final, so editing is not a way around the check. Every model
call, the writing and the check, goes through the usage ledger (features `cover_letter` and `screening_answers`).

### The posting is data, and the profile is only what was said
The job description is delimited and scrubbed in ai-service before it reaches a prompt, and core-api never builds a prompt.
An instruction found in it (`JOB_DESCRIPTION_INJECTION`) is removed, ignored, and reported to the reviewer as a `WARNING`
that quotes the sentence, so that the person can see what the posting tried to do. The warning is on the flag, never in the
letter.

Screening answers are made from what the user stated and nothing else: years of experience from the profile, and from the
preferences only the columns that carry a statement (salary floor and currency, locations, work modes). A column that is at
its default is not a statement and is not sent: "no sponsorship needed" is not sent unless the user set it. A question the
profile cannot answer comes back as `NEEDS_INPUT` with a hint, not as a guess, and the user types the answer. Typed answers
are checked like model text.

### The application pack is a pointer table with a state per part
`application_packs` holds one row per user, job and resume version: the options, and one entry per requested part (state
`PENDING`, `READY`, `FAILED` or `BLOCKED_BY_CAP`, the document id when ready, a typed error otherwise). It does not copy the
documents and has no foreign key to them, for the reason `generated_documents` has none to anything: an approved document
must outlive its pack, and a draft the user deleted shows as `MISSING` and can be made again, instead of breaking the pack.

- **Parts fail alone.** The CV, the letter and the answers are three independent model calls. If one fails, the others are
  kept: pack status is `COMPLETE`, `PARTIAL` or `FAILED`, and each failed part carries its own error. Making the user pay
  again for what worked would be a bug, not a safety measure.
- **The cap is per part.** When the daily allowance runs out mid-pack, the parts already made stay and the rest are
  `BLOCKED_BY_CAP` with `resetsAt`, the same `ai_daily_cap_reached` the rest of the API speaks. A retry before the reset is
  blocked again without a call.
- **Idempotent.** `POST /jobs/{id}/application-pack` with the same options returns the existing pack (200) and spends
  nothing; the first call is 201. Other options write the letter and the answers again and reuse the CV, which does not
  depend on tone or length. `include` omitted means all three parts; given empty it is a 400.
- **Retry** (`POST /application-packs/{id}/retry`) makes only the parts that failed, were blocked, or are missing, and
  recovers a pack abandoned in `GENERATING` by a crashed instance (an `updated_at` cut-off, as for tailoring).

### Rendering
A letter renders to PDF and DOCX through the P4.2 `rendering` module with the same constraints (single column, text in
reading order, Unicode intact). It takes the approved document only, as a resume does. The answers are not rendered: they
are text to paste into a form, and the API returns them as such.

## Consequences
- A letter costs a generation and a fact check; a pack costs up to three of each, and the ledger shows every call.
- `SUPERSEDED` rows accumulate until the account is deleted. They are small and the user can delete a draft; a retention
  rule is deferred until the ledger shows it matters.
- The claim extractor is a model, so the fact check is a strong filter and not a proof. An approved letter is the user's
  own statement of fact, as an approved resume is.
- `pack_in_progress` (409) is possible in a narrow window between two concurrent first requests; the client retries, and the
  unique index guarantees one pack and one set of model calls.

## Alternatives considered
- **A table per document type.** Rejected: it would copy the immutability trigger, the approval rules and the deletion hook
  three times, and any later fix would have to be made three times.
- **One model call that writes the CV, the letter and the answers together.** Rejected: one failure would lose everything,
  and the parts have different prompts, different caps and different retry needs.
- **Checking only the letter's structure.** Rejected: invented claims live in sentences, which is the reason the prose check
  exists.
- **Rendering the answers as a document.** Deferred: nobody uploads a file of answers into a form field.
