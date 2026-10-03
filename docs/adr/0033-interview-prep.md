# 0033. Interview preparation: questions and a grounded company brief

## Status
Accepted

## Context
P5.1 (`PLAN.md` section 7, interview preparation). For a job a user cares about, the product generates likely interview
questions and a short brief on the company and role. Two things make this riskier than it looks. First, a company brief is
exactly where a model is tempted to invent: funding rounds, acquisitions, headcount, culture, "recent news". JobFinder holds
only a job posting and a thin company record (name, domain, size, industry), so anything beyond those is not knowledge we
have. Second, the posting and the company text are untrusted input that goes in front of a model, and the resume is personal
data.

The rules of ADR 0020 (every model call is metered and capped), ADR 0029 (nothing the model wrote is trusted) and the
ai-service client pattern (service token, usage reported back and recorded by core-api) apply unchanged.

## Decision

### Two calls, two tiers, one endpoint
ai-service exposes one endpoint, `POST /v1/interview-prep`, and makes two independent model calls behind it:

- questions, on the strong tier (`questions_v1.md`): categorised `behavioral`, `technical` or `role_specific`, each with a
  rationale and a difficulty, written against the resume and the posting;
- the brief, on the fast tier (`brief_v1.md`): sections `ROLE_OVERVIEW`, `COMPANY_FACTS`, `SKILLS_AND_TOOLS` and
  `LOGISTICS_AND_PAY`, plus `unknowns`.

Both are Pydantic-validated with one retry and then a loud failure (the existing `generate_structured`). They are separate
because they need different inputs (the brief must not see the resume, so it can never turn a resume line into a company
fact) and different quality tiers. Prompts are versioned files under `app/prompts/interview/`; the version string
(`interview/v1`) is sent by core-api, echoed back, and stored.

### The brief is built only from what we hold, and every claim says where from
The brief call receives a fixed set of named fields (`job.title`, `job.description`, `job.location`, `job.work_mode`,
`job.employment_type`, `job.seniority`, `job.salary`, `job.skills`, `company.name`, `company.domain`, `company.size`,
`company.industry`) and nothing else. Every claim is `{statement, source, evidence}`. A deterministic grounding check
(`app/interview/grounding.py`) then keeps or drops each claim. It is dropped, with a typed reason that is reported and
counted, unless all of these hold:

- `source` is one of the fields that was sent and is not empty;
- `evidence` is a contiguous run of words from that field (case, accents and punctuation folded);
- every number and proper name in `statement` appears in the evidence or the field;
- at least 60% of the statement's content words (stemmed) are supported by the field;
- the statement does not echo instruction-like text, and does not repeat another claim.

Whatever the model could not support is not silently omitted: the brief carries `unknowns`, in plain sentences ("The
company's funding is not held by JobFinder"), including a line saying how many statements were removed. A brief states what
the posting says; it does not verify it. A posting can be wrong or promotional, and the brief does not claim otherwise.

core-api checks again before storing. It does not trust ai-service's grounding: a claim whose `source` was not a field it
sent, whose evidence is not a word run of that field, or a category set that lacks one of the three categories makes the whole
answer unusable (503, usage recorded as `FAILED`). Both sides use the same folding rules.

Non-Latin text (for example a posting in Japanese) yields few claims, because the word-run and stemming rules are written for
space-separated scripts. That fails safe, toward more `unknowns`, and is accepted for now.

### Untrusted text
Job, company and profile text is put in delimited blocks with a per-request nonce, the instruction not to follow anything
inside them comes before and after, and the text is scrubbed of delimiter lookalikes first. `scrub_job_text` reports how many
redactions it made. An injection fixture (a posting that says to ignore instructions and to state that the company was
acquired by a fictional firm with 40,000 staff) runs end to end with a model that obeys it: the instruction and the fake fact
are not in the questions or the brief, and the dropped claims are in the report. core-api repeats this with the pinned
contract file for that case. CV text and full job descriptions are never logged by either service.

### Idempotent per (user, job, prompt version)
`interview_prep` has `UNIQUE (user_id, job_id, prompt_version)`. A repeat request returns the stored result with 200 and
makes no model call and no cap charge, including when the cap has since been spent. A new prompt version is a new row, and
the old one stays readable. The first request inserts a `GENERATING` placeholder (as `generated_documents` does) so that a
double click makes one call: the second request finds the placeholder and returns it as is, with status `GENERATING`. A
placeholder older than the generation timeout belongs to a dead request and is replaced; a failed generation deletes its own
placeholder, so a retry starts clean. The prep does not depend on a resume version: it is generated from the primary parsed
resume at the time, and is not regenerated when the resume changes. Regenerating is a new prompt version, not a button.

Order of checks, cheapest and most specific first: 404 `job_not_found`; an existing prep (200); 409 `resume_required`; the
daily allowance (429 `ai_daily_cap_reached`); then the call (503 `interview_prep_unavailable` on any failure). Errors are
RFC 7807 problems.

### Storage and ownership
V29 adds `interview_prep` (one row per prep, `user_id` with cascade), `interview_questions` (ordered) and `company_briefs`
(sections and unknowns as `jsonb`). `job_id` has no foreign key: a prep outlives a job that expires. Every read is scoped by
`user_id` in SQL, and another user's prep gets the same 404 (`interview_prep_not_found`) as one that does not exist. The
`UserDeletionRequested` handler purges a user's preps.

### API, and one deviation from the brief
`POST /interview-prep` (`{jobId}`; 201 created, 200 existing) and `GET /interview-prep/{id}`. The task text writes
`/api/v1/...`; core-api serves no such prefix (every other controller is at the root and the gateway adds the prefix), so
these follow the codebase. The OpenAPI document and the generated TypeScript client are regenerated.

### Usage
core-api records both calls under one ledger feature, `interview_prep` (user, model, tokens, cost, latency, prompt
version), through the shared `AiUsageLedger`; ai-service's own labels are `interview_questions` and `interview_brief`. If the
brief call fails after the questions call succeeded, the questions call's usage is still reported and recorded as `FAILED`.

### The fake provider
`LLM_PROVIDER=fake` answers both features with a deterministic heuristic provider that quotes the fields it is given, so the
module runs end to end, and the grounding check has something real to check, with no API key.

## Consequences
- Roughly two calls' cost per prep (one strong, one fast), charged once per job and prompt version.
- The brief is short and sometimes mostly `unknowns`. That is the intended failure mode; it is better than a fluent invention.
- A model that writes a good but unsupported claim loses it. The `dropped` report makes the rate measurable, and a new prompt
  version is the way to improve it.
- Adding sources (news, funding data) later means adding named fields and a prompt version, not loosening the check.
- P5.2 and P5.3 (answer practice, mock interviews) build on the stored questions and are not part of this change.
