# 0017. Profile, preferences and the onboarding / review flow

## Status
Accepted

## Context
P1.6 is the first UI on top of the `profile` module, but core-api had only the tables (V5–V8) and the resume
endpoints: no way to read the parsed CV, to save a profile or preferences, and the parser's grounding `warnings`
(ADR 0016) were dropped on the floor. Parsing is asynchronous, so the review screen can open while a resume is
`PENDING`, after it `FAILED`, or once `PARSED`. `PLAN.md` leaves open what "seniority" holds and how onboarding
knows it is finished.

## Decision

### core-api
- **Endpoints**, all owned by the caller from the access token (no ID in any body; extra `userId` fields are
  ignored and a test proves it):
  `GET/PUT /profile`, `GET/PUT /preferences`, `GET/PUT /resumes/{id}/content`. A user who never saved gets an empty
  profile / preferences back (200, not 404). `PUT` replaces the whole resource.
- **Profile vs. CV content.** `profiles` holds the person's details; the experience / education / skills /
  projects / certifications stay in `resume_versions.structured` (PLAN §5). The editor shows one set of contact
  fields: on save the CV's `contact` block is rebuilt from the profile fields (the CV's own email is carried over).
- **Content shape = parser shape.** `ResumeContent` is schema version 1 in snake_case with the same limits as
  ai-service's `ParsedResume`, so one shape runs parser → database → editor → database. Names are explicit
  `@JsonProperty` (springdoc does not see Jackson 3's `@JsonNaming`, so the spec would have disagreed with the
  wire). `schema_version` is set by the server.
- **Edits are versions, cheaply.** The parser's `UPLOAD` version is never touched. The first save adds an `EDIT`
  version; later saves update that `EDIT` version in place while it is the latest, so a user who keeps tweaking does
  not pile up rows. Saving is allowed in every parse state: a `FAILED` or slow parse must not block the user. A parse
  that finishes after an edit only fills version 1 (ADR 0016's guarded writes) and never replaces the edit. The
  resume row is locked `FOR UPDATE` during a save so version numbers cannot collide.
- **Grounding warnings are kept** (V10, `resume_versions.parse_warnings`, `[{path, code}]`). core-api reduces them
  to well-formed string pairs (max 200) and drops anything else rather than failing a good parse. They belong to the
  upload version only, so they disappear once the user has saved an edit.
- **Onboarding is complete when preferences exist.** `GET /profile` returns `onboardingCompleted`, derived from the
  `preferences` row. Saving preferences with everything empty is how a user skips that step. No new column.
- **Validation is server-side** (Jakarta Validation on the record DTOs) and does not rely on the browser: length
  and count caps, `YYYY` / `YYYY-MM` dates, http(s)-only URLs (a stored link is never `javascript:`), email shape,
  a real ISO 4217 currency (required with a salary floor), enum work modes and seniority. Cross-field rules (a
  current role has no end date; an end date is not before its start) are checked in the service. Free text is cleaned
  in the DTO constructors (`CleanText`: control characters become spaces, which also keeps a NUL from making
  Postgres reject the JSON; blanks and case-insensitive duplicates are dropped from lists).
- **Usable 400s.** `GlobalExceptionHandler` now answers bean-validation failures with `code: validation_failed`
  and an `errors: [{field, message}]` list (messages only, never the rejected value).
- **Nulls are omitted** from these DTOs (`@JsonInclude(NON_NULL)`), so the generated client's "optional" types are
  true. (A `null` had been read as the text "null" in a number input, which silently blocked a form submit.)
- Persistence uses `JdbcClient` upserts (`ON CONFLICT (user_id)`), not JPA entities: `jsonb` and `text[]` columns
  are simpler that way, and there is no entity to leak.
- **Seniority** is an enum: `INTERN, JUNIOR, MID, SENIOR, LEAD, EXECUTIVE`. **Work modes**: `REMOTE, HYBRID, ONSITE`.

### web
- **Onboarding is three routes** (`/onboarding/cv`, `/review?resume=<id>`, `/preferences`), so reload and the back
  button stay in step. `/dashboard` redirects a user who has not finished onboarding (`RequireOnboarding`,
  client-side like the auth guards in ADR 0014, and **fail-open**: if the profile cannot be loaded the page shows
  rather than locking the user out). It only redirects on a settled answer, because a cached "not onboarded" is
  stale for a moment after preferences are saved.
- **The review screen is a state machine over the resume's parse state**, never assuming parsing is done:
  `PENDING` shows an in-progress message and polls (2 s) with a way out ("Fill it in myself", and a "taking longer
  than usual" note after 45 s); `PARSED` pre-fills every section and flags ungrounded employers / schools / projects
  and dropped skills; `FAILED` explains why from the stable `parseError` code and leaves the form empty to fill in.
  The form state is created once when the screen is ready, so polling or a refocus refetch can never overwrite
  what the user typed. Warnings stay attached to the right item after others are removed (items keep their
  original index).
- **Profile page** (`/profile`, `/profile/preferences`, `/profile/resumes`) reuses the same editor and preferences
  form. The resume list polls while any CV is `PENDING` and offers make-primary, download, delete (with a confirm
  step) and upload. Editing another CV's content is `/profile?resume=<id>`.
- Same patterns as P1.3: the generated client only, through `/api/core` (ADR 0011), the existing session and auth
  middleware, TanStack Query, plain controlled forms with native attributes (no form library), native
  `select` / `textarea` / checkbox styled like the existing inputs.
- **E2E.** Real CV parsing needs an LLM, which E2E must not call (and a local stack has no key), so
  `e2e/onboarding.spec.ts` stubs exactly one thing, `GET /resumes/{id}/content`, to script PENDING → PARSED (with a
  warning) and FAILED. The upload, every save (validated server-side, including a refused `javascript:` link) and the
  reads after a reload hit the real stack. Parsing itself is covered by ResumeParsingTests and ai-service's tests.
  Signup is rate limited to 5 per hour per IP, and the suite now uses 4 of them; the next spec that signs up needs
  the limit raised for E2E (or a shared signed-in state).

## Consequences
- There is still no re-parse endpoint: a CV that failed is replaced by uploading another (or filled in by hand).
- The estimated "years of experience" is a hint from the earliest start year, marked as such in the form.
- The profile's contact fields and the CV's `contact` block are kept in step by the editor, not by the database.
- A user who never reaches the preferences step is sent back to onboarding from the dashboard, but `/profile` stays
  reachable.
