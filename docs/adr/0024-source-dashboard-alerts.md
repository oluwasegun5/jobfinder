# 0024. Source dashboard and alerts

## Status
Accepted

## Context
P2.7 gives an admin a view of the ingestion machinery of ADR 0018 to 0021: which sources exist, how healthy they are,
what their last run did, a switch to take a source out of the schedule, a button to run one now, the run history,
and an email when a source breaks. `PLAN.md` §6 and §13 leave the alert rules open ("alerts on 0 jobs / error
rate > 20%"), so this ADR defines them exactly, because an alert that fires on noise gets muted and one that stays
quiet on a dead source is worse than none.

## Decision

### Admin API
Everything is under `/admin/ingestion/**`, so the `ADMIN` role rule of the security configuration covers it (401 without
a token, 403 for a signed-in user). The controller lives in the `admin` module and talks to the `ingestion` module
through a new public interface, `SourceAdminService`; the existing `IngestionService` stays as it was.

| Endpoint | What it does |
|---|---|
| `GET /sources` | Every registered source: code, kind, `enabled`, `schedule` (`SCHEDULED`, `DISABLED`, `UNAVAILABLE` for a missing API key with the `unavailableReason`, `SCHEDULER_OFF`), `health`, `lastRunAt`, `nextDueAt`, `running`, enabled and total targets, the latest finished run with its counts, and the open alerts. |
| `PUT /sources/{code}/enabled` | Sets `sources.enabled`. |
| `POST /sources/{code}/runs` | Starts a run in the background: `202`, or `409 run_in_progress` when the source's lock is held, or `409 source_unavailable` for a missing key, or `404`. |
| `GET /runs?source=&page=&size=` | The run history, newest first, page/size (default 20, at most 100), with per-run counts, `targets`, `errorSummary`. |
| `POST /targets` | As before, with the two fixes below. |

- **Disabling.** The scheduler already asks `SourceStore.findEnabled()` on every tick, so a disabled source is not
  started from the next tick (at most 60 s later) and a re-enabled one is picked up as soon as it is due. Nothing needed
  to change there; tests now pin it (`SourceAdminServiceTests`). A run already in progress is **not** interrupted: a
  fetch has no safe cancel point, half a target is worse than a whole one, and the run's own transaction-per-target
  design means letting it finish leaves consistent data. It simply is not followed by another scheduled run. A
  run started by hand on a disabled source works, as ADR 0018 says: "enabled" decides what the scheduler picks up,
  not what an admin may trigger.
- **Triggering.** `IngestionService.runNow` blocks, which is wrong for an HTTP thread. `startAsync` takes the same
  ShedLock lock (`ingestion:<code>`) on the calling thread, so "already running" is known before the response is
  written (the 409), then runs the pipeline on a virtual thread and releases the lock when it ends. It therefore
  never overlaps a scheduled run, here or on another instance. The response carries no run id, because the run row
  does not exist yet; the page polls the list. A source whose lock was taken by a crashed instance answers 409 until
  `lock-at-most-for` passes, as for the scheduler.
- **`running`** is "a RUNNING run row younger than `lock-at-most-for`", so the leftover row of a crashed instance
  does not show as running forever.

### What the dashboard needs from the data
- `ingestion_runs.targets` (V21): how many targets a run attempted. The error rate needs the denominator, and an
  "empty" run is only suspicious if it had something to fetch. Runs from before the migration keep 0, which both
  rules read as "unknown", so deploying cannot raise an alert about history.
- `source_alerts` (V21): one row per (source, rule) with `active`, `first_fired_at`, `last_notified_at`,
  `occurrences`, `notifications`. This is the de-duplication state; it also feeds the list.
- An index on `ingestion_runs (started_at desc)` for the all-sources history.

### Alert rules
Alerts are evaluated by `SourceAlerts` at the end of every run, in the run's own thread while it still holds the
source's lock, so two evaluations of one source never overlap. A failure to evaluate or to send is logged and never
fails or loses the run.

**ZERO_JOBS.** A run breaches when *all* of these hold:
1. it ended `SUCCEEDED` (every target fetched without error) and attempted at least one target;
2. its adapter returns full listings (`fullListing()`), because an incremental adapter legitimately answers "nothing
   new";
3. it fetched 0 postings; and
4. the latest earlier `SUCCEEDED` run with targets fetched at least `zero-jobs-min-previous` postings (default 5).

*Why those choices.* "Fetched" counts postings the source returned, before normalization, so a normalizer bug
does not look like a dead source (and a dead source does not hide behind it). A source that has never returned
anything (a first run, a legitimately empty feed) has no baseline and never alerts. The threshold of 5 rather than
1 is for tiny feeds: an aggregator query that oscillates between 1 and 0 would otherwise open and close an alert
every cycle. A run with errors is not a zero-jobs run, since the failure is the error-rate rule's business and zero
postings from a failed target says nothing. While the alert is open the rule stays in breach for as long as clean runs
keep fetching nothing, whatever the baseline (otherwise the second empty run, whose "previous run" is itself empty,
would clear it). The first run that fetches anything clears it. A run that is skipped by 1, 2 or 4 (PARTIAL, no
targets, incremental) changes nothing: it neither opens nor clears.

**ERROR_RATE.** `errors / targets attempted` for the run, where an error is a target that failed after its retries
(ADR 0018). It breaches when the rate is **strictly greater** than `error-rate-threshold` (0.2; exactly 1 of 5 is
fine) and the sample is big enough: at least `error-rate-min-targets` targets (default 5), *or every target failed*.
The sample rule exists because one dead board among three (33%) is a bad target, not a bad source, while a
single-target source (an aggregator with one search) that fails entirely must still be reported. The rule is
per run rather than over a window: runs are hours apart, the counts are exact, and a window would delay the alert
without making it more accurate. Any run that does not breach clears the alert; a run with no targets changes nothing.

**Throttling.** One alert per (source, rule) until recovery. The first breach opens the alert and sends the email.
Further breaches only increment `occurrences`. If the alert is still open `re-alert-interval` (default 24h; zero
disables) after the last email, it is sent again, saying how long it has lasted. Clearing re-arms it: the next breach
is a new episode with a new email. There is no recovery email (the list shows it), to keep the volume at "one
email when something breaks".

**Delivery.** `app.ingestion.alerts.recipients` (env `INGESTION_ALERT_RECIPIENTS`, comma separated) via Spring's
`JavaMailSender` (Mailpit locally, like the auth emails). With no recipients the alert is logged at WARN and
throttled the same way, so a fresh install does not need a mail setup. Malformed addresses are dropped (an address
with a line break would be a header injection). If sending fails, the alert is **not** marked as announced, so the next
run retries, and the failure is logged. The mail timeouts are set (`spring.mail.properties.mail.smtp.*`), so a mail
server that stops answering delays a run by seconds, not indefinitely.

**Content.** Source, kind, rule, the numbers (targets, errors, fetched, the previous baseline), run id and time, and
a link to `/admin/ingestion` on `app.ingestion.alerts.web-base-url` (`WEB_BASE_URL`, as for the auth emails). It
deliberately contains no error messages, target identifiers or URLs from the run: an adapter promises not to put
credentials into its errors, but the email is the one place that leaves our system, so it carries only counts and the
admin page (behind the admin sign-in) has the detail.

**Metric.** `ingestion.alerts.fired{source,rule,delivery}` counts each announcement; `delivery` is `email`,
`log` (no recipients) or `failed`.

### Two fixes to `POST /admin/ingestion/targets` (known gap from P2.3)
- `companyName` is no longer a required field of the request. The service already knew the rule: an ATS board needs a
  company, an aggregator search must not have one (ADR 0021). The request validation contradicted it, so an
  aggregator target (`gb:software engineer`, `all`) could not be added. A missing company on an ATS board is now an
  `invalid_target` 400 from the service instead of a `validation_failed` one from the validator.
- The 201 response is documented in OpenAPI (it was only inferred as 200).

### Web
`/admin/ingestion` (sources: health badge, schedule state, last run counts, open alerts, an enable switch, a "Run now"
button with feedback, and the page polls while a run is in progress) and `/admin/ingestion/runs` (history, filter by
source, page buttons). Both are shown only to users whose `role` is `ADMIN`; others see a "not allowed" message and
the navigation item is hidden. The server is the real gate: the UI check is for courtesy.

## Consequences
- Disabling is cooperative: the source finishes the run it is in. If that ever matters (a runaway source), a cancel
  needs cooperation from the adapters and is a separate piece of work.
- Alert state is per source and shared by all instances (it is in Postgres, and the source's lock serializes the
  evaluation), unlike the circuit breaker.
- Hand-triggered runs are alerted on exactly like scheduled ones; an admin who runs a source that is broken gets its
  alert sooner, which is the point.
- `targets = 0` on old runs means the history page shows "0 targets" for runs before V21.
- Alerts are not yet muted when a source is disabled: a disabled source stops producing runs, so its open alert stays
  open (and visible in the list) until it runs again, which is the honest state.
