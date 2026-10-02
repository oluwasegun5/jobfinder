# 0032. Application tracker, reminders and the follow-up draft

## Status
Accepted

## Context
P4.5 (`PROMPTS.md`: "`applications` module: applications, application_events, reminders. Kanban board UI (later), notes,
manual entries for jobs found elsewhere, follow-up reminders (email), AI follow-up email draft"). This ADR covers the
backend; the board is a later change. A person applies through a company's own site, so the platform cannot observe it:
the tracker records what the user tells it, keeps the history of what happened, and emails a reminder when the user asked
for one. It also drafts a follow-up email, which is prose about the user's own career, so the rules of ADR 0029 and
0031 apply unchanged: nothing the model writes is trusted, the posting is data, and every call is metered (ADR 0020).

## Decision

### One endpoint serves "I applied" and the manual entry
`POST /applications` takes a `jobId` (the title and company are copied from the job; any sent are ignored) or no job and a
`title` (and optional `company`, `url`). `status` defaults to `APPLIED`; `packId` and the ids of an approved resume, cover
letter and screening answers record what was used, and are accepted only when they belong to the caller, are approved,
have the matching type and, for an application from a job, were made for that job. The ids are plain UUID columns with no
foreign key, as in `application_packs` (ADR 0031): an approved document outlives its pack, and a draft the user deleted
must not block the tracker.

There is one application per user and job (a partial unique index on `(user_id, job_id)`). Creating it again returns the
one that exists with 200 instead of 201, whatever the request says, so a double click on "I applied" is safe; a
concurrent second request that loses the race to the index is answered the same way. To move an application you change
its status. An application is capped per user (`app.applications.max-per-user`, 409 `application_limit_reached`).

### Statuses and an append-only history
The board's columns are `SAVED`, `APPLIED`, `SCREENING`, `INTERVIEW`, `OFFER`, `REJECTED` and `WITHDRAWN`.
`POST /applications/{id}/status` allows any move except back to `SAVED` once the application has left it (409
`invalid_transition`): backwards moves are allowed because cards are dragged back and rejections are sometimes mistakes.
The first move out of `SAVED` into a status that means "applied" sets `appliedAt`. The same status is a 200 no-op that
records nothing.

Every change is a row in `application_events` (from, to, note, time), the first having no `from` because it records the
status the application was created with. The table is append only: a trigger refuses every `UPDATE`. `DELETE` is allowed
so that deleting an application, or the account, can cascade; the history lives and dies with its application. `REJECTED`
and `WITHDRAWN` end an application: its pending reminders are cancelled and none can be added.

### Reminders are rows, and sending is claimed in the database
A reminder is an email to the user at `dueAt` (future, within a year) about one application, of kind `FOLLOW_UP`,
`INTERVIEW` or `CUSTOM`, with an optional note, at most 20 pending per application. None is added automatically: moving
to `APPLIED` creates no reminder, the user chooses. A reminder is `PENDING`, `SENT` or `CANCELLED`, and a cancelled one
keeps its reason (`USER`, `APPLICATION_CLOSED`, `EMAIL_DISABLED`, `NO_RECIPIENT`, `SEND_FAILED`), so the list can say why
nothing arrived.

A `@Scheduled` job (every 5 minutes) runs under a ShedLock lock, in the `shedlock` table the other schedulers use. The
lock is an optimisation, not the guarantee. Exactly-once comes from the claim: one
`UPDATE reminders ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING`, which marks a batch claimed and counts the
attempt in the same statement, so two instances, or a run that overlaps the lock's expiry, can never hold the same
reminder. The claim doubles as the retry rule: a reminder whose claim is older than `retry-after` (15 minutes) can be
claimed again, which is both how a failed send is retried and how a claim whose sender died is taken over. After
`max-attempts` (3) the reminder is cancelled as `SEND_FAILED`. One failing reminder does not stop the rest of the batch.

The email goes through a new public interface of the `notifications` module, `UserMail`, so the tracker does not touch the
mail sender and the mail honours the same settings as all optional mail: nothing is sent to a user who switched email
notifications off (`EMAIL_DISABLED`) or whose account cannot receive it (`NO_RECIPIENT`). The application's
title and company and the user's note are escaped in the HTML part. The job can be switched off with
`APPLICATION_REMINDERS_ENABLED=false`; the test profile switches it off so nothing fires on its own, and the tests run the
sender and the scheduler by hand against a real mail server (Mailpit).

### The follow-up draft is a draft
`POST /applications/{id}/follow-up-draft` returns `{subject, body, tone, length, model, promptVersion}` and stores
nothing and sends nothing: the user reads, edits and sends it themselves. It needs a parsed primary resume
(409 `resume_required`) and an application that has left `SAVED` (409 `not_applied_yet`).

core-api sends ai-service only what the tracker knows: the title, company, status, the date the user applied, the job
description when the application came from a job that still exists, the notes typed for this draft, and the resume. The
notes kept on the application are not sent; they are the user's private record. ai-service's `/v1/follow-up-email` (prompt
`follow_up_email/v1`) builds the letter around code-chosen salutation, closing and signature, delimits and scrubs the
posting like the cover letter does, and runs the draft, subject included, through the fact check against the resume, the
application's own facts and the notes. A draft with a blocking flag is discarded: 503 `follow_up_rejected`, with the cost
still in the ledger. ai-service down or answering badly is 503 `follow_up_unavailable`. The daily cap is checked before
the call (429 `ai_daily_cap_reached`) and the call is metered as the feature `follow_up_email`.

### Account deletion
The `applications` module listens for `UserDeletionRequested` and deletes the user's reminders, events and applications in
the deleting transaction, only theirs. The tables also reference `users` with `ON DELETE CASCADE`, so a deletion cannot
leave rows behind even if the listener is removed.

## Consequences
- The tracker holds what the user said. Nothing marks the job as applied in the `jobs` module, and the job's apply link is
  not exposed to this module, so a `url` comes from the client.
- A reminder cancelled as `EMAIL_DISABLED` or `NO_RECIPIENT` is not brought back when the user turns notifications on
  again; they add a new one.
- If a process dies after the mail server accepted the message and before the row is marked `SENT`, the claim is taken over
  after the retry delay and that one email can be sent twice. Avoiding it would need the mail server to deduplicate, which
  it does not.
- The follow-up draft is a strong filter and not a proof, like the other fact checks: the user is the author of what they send.

## Alternatives considered
- **A reminder per default on `APPLIED`.** Rejected for now: the user decides whether and when to be emailed; it can be a
  setting later.
- **Relying on ShedLock alone for exactly-once.** Rejected: a lock that expires mid-run, or a clock that drifts, lets two
  instances send. The claim holds without it.
- **A separate Kanban entity.** Rejected: the board is the same rows grouped by status (`grouped=true` on the list).
- **Sending the follow-up for the user.** Rejected: an email written by a model and sent without a read is the failure the
  rest of P4 exists to prevent.
