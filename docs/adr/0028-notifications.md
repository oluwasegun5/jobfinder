# 0028. Digests, alerts and unsubscribe

## Status
Accepted

## Context
P3.4 (`PLAN.md` section 7). A user can search (ADR 0023), is matched to jobs (ADR 0026) and has a feed that learns from
saves and hides (ADR 0027). Nothing yet tells them when something good appears while they are away. P3.4 adds the
`notifications` module: saved searches with a frequency, a daily or weekly digest of the best new matches, an instant
alert for a strong match, notification preferences and unsubscribe links. The hard parts are not the email: they are
sending **exactly once** (re-runs, retries, two instances), **never to someone who must not get it** (unverified,
deleted, unsubscribed), **never repeating a job**, **not spending model money**, and making the unsubscribe link work
for a person who is not logged in without becoming a way to attack or enumerate accounts.

## Decision

### A `notifications` module on public APIs only
The module owns four tables (V24, below) and talks to other modules only through new narrow public APIs; none of them
depends back on `notifications`, so there is no cycle and `ApplicationModules.verify()` passes.

- `jobs.NewJobsSource`: "active jobs stored in this window matching these criteria", excluding the user's hidden and
  applied jobs, with display cards. It reuses the search repository's filters, so a saved search means what the same
  filters on the jobs page mean.
- `feed.FeedMatches.strongMatches`: the user's best cached model-scored matches (see "No model spend").
- `identity.MailRecipients`: the address of an **active, not deleted, email-verified** user, else nothing.
- `matching.MatchesRefreshed`: an event published when a ranking run scored jobs with the model.

### Migration V24 (only new migrations, nothing edited)
- `saved_searches`: the criteria as plain columns (`q`, `work_modes`, `employment_types`, `seniorities`, `countries`,
  `city`, `min_salary`, `salary_currency`; arrays are `text[]`), `frequency` (`INSTANT | DAILY | WEEKLY | OFF`) and
  `last_run_at`, the watermark. Columns, not `jsonb`: they are checked by the database, stay in step with the `GET /jobs`
  filters, and can be queried. `companyId` and `postedWithinDays` are deliberately **not** saved: the first names one
  employer (a follow, not a search) and the second is meaningless for "new since last time".
- `notification_preferences` (one row per user, created on first save): `email_enabled`, `digest_enabled`,
  `digest_frequency`, `digest_hour`, `digest_weekday`, IANA `timezone`, `instant_enabled`, `instant_threshold` (50 to 100,
  default 85), and two unsubscribe stamps (`digests_unsubscribed_at`, `marketing_unsubscribed_at`).
- `notification_log`: the outbox and audit trail, `UNIQUE (user_id, kind, scope, window_key)`, `status`
  `PENDING | SENT | FAILED | SKIPPED`, attempts, `next_attempt_at`, `job_ids` (what the email listed), `last_error`.
- Unsubscribe tokens have **no table** (see below).
- All three user tables cascade on user deletion, and `NotificationDeletionHandler` deletes them explicitly on
  `UserDeletionRequested`, like every module's purge.

### Digests and alerts are opt-in
A new user receives nothing optional until they turn it on (`digest_enabled` and `instant_enabled` default to false).
The only email sent without a switch is what they asked for by creating a saved search with a frequency. This is the
safe default for a mailbox we do not own, and the unsubscribe flags can then only ever be set by the user.

### Content rules
- **"For you" digest**: the top N (default 10) strong matches, taken from cached model scores (feed score at least 70,
  scored in the last 14 days), excluding jobs the user hid or applied to and jobs listed in any earlier **sent** digest or
  alert (`notification_log.job_ids`). It lists nothing it listed before. Subjects never contain a job title.
- **Saved-search digest**: active jobs matching the criteria whose `created_at` is after the search's watermark and before
  the run's cut-off, newest first, N listed and "and M more" linking to the search on the web.
- **No empty emails**: with nothing to list the window is recorded `SKIPPED` (so it is not retried) and nothing is sent.
- HTML and text parts, every untrusted string (titles, companies, search names) escaped for HTML and stripped of control
  characters in the subject and headers, only `http(s)` URLs used for attribution links, aggregator attribution on each
  job ("via Adzuna") and the credits footer the sources' terms require, job links to the **web** job page, dates in the
  user's timezone (UTC when none) with the zone named in the "Sent" line.

### Saved-search watermark
`last_run_at` starts at creation, so a new search is about jobs from now on. It advances only when an email was
`SENT` or the run was `SKIPPED` because nothing matched; a failed send keeps it, so the retry finds the same jobs.
It is reset to now when the criteria change or the search is switched from `OFF` to a frequency, because jobs that
appeared while it was something else are not "new" for it. Runs stop **two minutes before now** (`settle-delay`): an
ingestion transaction that began before the cut-off may still be committing, and the next run picks those jobs up
rather than missing them.

### When a digest is due
Each digest has a scheduled moment in the user's own time zone: their hour (daily) or their hour on their weekday
(weekly). An hourly job (`0 5 * * * *` UTC) finds the most recent such moment at or before now; it is due if that is not
more than six hours old (`max-lateness`), and the moment's local date names the **window** (`D:2026-10-01`,
`W:2026-09-28`). A run that comes later than that does not send a "morning" digest in the evening: the window passes.
Daylight-saving changes are handled by resolving the local time with the zone's rules (a skipped local hour moves to the
next valid one). Timezone must be an IANA region (`Africa/Lagos`) or `UTC`; offsets and abbreviations such as `EST` are
rejected because they cannot follow daylight saving.

### Exactly-once sending: the log is the lock
Before sending, a sender **claims** the window by inserting a `PENDING` row (`ON CONFLICT DO NOTHING`); only the sender
whose insert wins proceeds. That makes a re-run, a second instance and a retry loop all safe without a distributed lock
(ShedLock only keeps two instances from doing the same scan). The window keys are `D:`/`W:` for digests, `M:<hash of
the job ids>` for a match alert (so the same set of jobs is alerted once) and `S:<watermark millis>` for a search alert.
A send that fails is marked `FAILED` with the exception **class name only** (never an address, token or server message),
and the next run retries it after 15 minutes, then 30, up to three attempts and only while its window is open. A claim
left `PENDING` by a crashed sender is taken over after 30 minutes. The error of one user never stops the batch; the run
returns a summary and records metrics: `notifications.emails` (by kind and outcome), `notifications.alerts.rate_limited`
and `notifications.run.duration` (by job).

### Instant alerts
- **Matches**: when a ranking run scores jobs with the model (`MatchesRefreshed`), an `@Async` listener looks at that
  user's cached scores and alerts the jobs at or above their `instant_threshold` that were never listed in a sent email,
  newest score first, at most 5 per email. The listener catches every error: ranking never fails because of mail.
- **Saved searches** set to `INSTANT` are polled every 10 minutes (ShedLock `notifications:instant`) and alert when new
  jobs match, with the same watermark rules.
- **Limits**: at most **3 alert emails per user in any 24 hours**, shared by both kinds. What the cap holds back is not
  lost: it is still unsent, so the next alert or the digest includes it, and a held-back search keeps its watermark.
- **Never for someone who cannot be mailed**: a search owned by an unverified or disabled user advances its watermark
  without sending, so turning mail on later does not release a flood.

### No model spend
Neither the digest nor an alert calls ai-service or touches the AI allowance. They read only the score cache (ADR 0026),
which the nightly run and the user's own browsing already fill. A job with no valid model score is simply not in a "For
you" email yet. Alerts are triggered by a run that was happening anyway.

### Who is mailed
Only users who are active, not deleted, **verified**, have `email_enabled`, have not used a digests or all-mail
unsubscribe for that kind, and have turned that kind on. Anything else is skipped silently. These checks are made at
send time, not only when scanning, so an unsubscribe a second ago still wins.

### Unsubscribe
- **Tokens are signed, not stored**: `base64url(payload).base64url(HMAC-SHA256)`, payload
  `1|userId|SCOPE|searchId-or-dash|expiry`. The key is `app.notifications.unsubscribe-secret`, or, when blank, an HMAC of
  the JWT secret under a fixed label (so no extra secret is needed to start, and the two uses cannot be confused). Tokens
  are valid for two years: people unsubscribe from old mail. They are not single-use; unsubscribing is idempotent.
- **Scopes**: `SAVED_SEARCH` (one search off), `DIGESTS` (the For-you digest and every saved-search digest),
  `INSTANT_ALERTS` (match alerts and INSTANT searches), `MARKETING` (all optional mail). Each mail carries the narrowest
  scope that matches it (a digest carries `DIGESTS`, a search email `SAVED_SEARCH`, an alert `INSTANT_ALERTS`), and the
  footer also links the settings page.
- **Endpoints, public** (`/notifications/unsubscribe/{token}`, GET and POST only): `GET` **describes** what the link would
  do and changes nothing (so a mail scanner that prefetches links does no harm), `POST` does it and is the RFC 8058
  one-click target. A forged, altered or expired token is `404 invalid_unsubscribe_link`, the same answer for every kind
  of bad token. A **genuine** token always returns 200, even when the account or search is gone (the foreign-key failure
  is swallowed) and on repeats, so the endpoint cannot be used to learn whether an account exists.
- **Headers**: `List-Unsubscribe: <{api}/notifications/unsubscribe/{token}>` and
  `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (RFC 8058), `Precedence: bulk`, `Auto-Submitted: auto-generated`.
  `{api}` is `app.notifications.public-api-url`, or the web app's `/api/core` proxy (ADR 0011) when blank, because the
  mail client posts to a public address. The footer link goes to the web landing page `/unsubscribe?token=` where the
  person confirms.
- **Transactional mail is never affected**: verification and password-reset mail (`identity.AuthMailer`) never reads
  these tables; a test unsubscribes a user from everything and still gets a reset email.
- Saving preferences with digests or alerts turned **on** clears the matching earlier unsubscribe: choosing to opt in
  again in the settings page is as explicit as the click that opted out.

### API
`GET/PUT /notifications/preferences` (PUT replaces the whole settings object), `GET/POST /saved-searches`,
`GET/PUT/DELETE /saved-searches/{id}` (another user's id is a 404, never a 403), at most 20 searches per user (409
`saved_search_limit`), empty criteria 400 `empty_search`, a salary without a currency 400 `salary_currency_required`.
Errors use the existing problem format.

### Web
`/settings/notifications` (preferences and the list of saved searches with frequency, rename and delete), a "Save this
search" action on the jobs search page that stores the current filters, and a public `/unsubscribe` landing page outside
the authenticated layout. All use the generated client only.

### Operations
`app.notifications.digest.enabled`, `.cron`, `.zone`, `.max-items`, `.min-score`, `.max-lateness`; `.instant.enabled`,
`.poll-interval`, `.max-per-day`, `.max-items-per-alert`; `.delivery.max-attempts`, `.initial-backoff`, `.stale-after`,
`.log-retention`; `app.notifications.web-base-url` (links in mail), `.public-api-url`, `.mail-from`,
`.unsubscribe-secret`. Compose and `.env.example` carry the `NOTIFICATIONS_*` variables; the test profile switches both
schedulers off. Log rows older than `log-retention` (180 days) are deleted by the digest job; that also lets a job older
than that be listed again, which is intended. Locks: `notifications:digest` and `notifications:instant`.

## Consequences
- A re-run, a retry, two instances or a restart in the middle of a batch never sends a window twice, and a job is not
  repeated in later digests or alerts. The price is one small insert per email.
- Digests are per hour of the day, so a run that is down for more than six hours loses that morning's digest rather than
  sending it late. The metrics and the logged summary show it.
- A job only reaches "For you" mail once the model has scored it, so a brand-new user has no digest until their first
  look or the nightly run has filled the cache. That is the cost of spending nothing.
- Unsubscribe tokens live for two years and cannot be revoked individually; rotating the secret invalidates all of them.
  They only ever switch mail off, so a leaked one lets someone stop another person's mail, not read or change anything.
- For production deliverability, the sending domain needs SPF and DKIM and `mail-from` must be an address on it;
  List-Unsubscribe headers help but do not replace that. Local development sends to Mailpit with none of this.
- Not done here: per-search digest time, a digest preview in settings, push or SMS channels, an admin view of the
  notification log, localisation of the emails.
