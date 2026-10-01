# 0021. Aggregator adapters

## Status
Accepted

## Context
P2.4 adds the five aggregator adapters of `PLAN.md` section 6 tier 2 that the task names: Adzuna, JSearch (RapidAPI),
Remotive, Arbeitnow and Remote OK. Unlike the ATS boards of ADR 0020, aggregators have quotas, two of them need a
key, and every one of them has terms about crediting the source. This ADR records what each API looks like today,
what its terms ask for, and how the adapters keep inside the quotas and store the credit. Everything was checked on
2026-10-01. Remotive, Arbeitnow and Remote OK were called live (read-only GETs) and their responses are the basis of
the mapping. **Adzuna and JSearch need a key that this task does not have, so their request and response shapes come
from their documentation and from third-party summaries of it, and their adapters are tested with synthetic
fixtures only.** Where that matters it is said below.

## What each source offers

| | Endpoint (all `GET`) | Auth | Pagination | Free-tier limits |
|---|---|---|---|---|
| Adzuna | `https://api.adzuna.com/v1/api/jobs/{country}/search/{page}?app_id=&app_key=&results_per_page=&what=&where=&max_days_old=&sort_by=date` | free `app_id` + `app_key`, in the query string | page number in the path; at most 50 results a page | documented defaults: 25 calls a minute, 250 a day, 1,000 a week, 2,500 a month |
| JSearch | `https://jsearch.p.rapidapi.com/search?query=&page=&num_pages=&date_posted=&country=` | RapidAPI key in `X-RapidAPI-Key`, plus `X-RapidAPI-Host: jsearch.p.rapidapi.com` | `page`; up to 10 results a page; `num_pages` fetches several per call | free plan: 200 requests a month |
| Remotive | `https://remotive.com/api/remote-jobs[?category=slug]` | none | none: the whole feed in one response | advises at most 4 requests a day; blocks more than 2 a minute |
| Arbeitnow | `https://www.arbeitnow.com/api/job-board-api?page=N` | none | `page`; about 325 jobs (2.5 MB) a page; `links.next` is null on the last page | none documented ("please do not abuse"); refreshed hourly |
| Remote OK | `https://remoteok.com/api` | none | none: the latest ~100 jobs in one response | none documented |

### Adzuna (shape from documentation, not called with a key)
- Response: `{count, mean, results: [...]}`; a result has `id`, `title` (may contain `<strong>` around matched words),
  `description` (**a short snippet**, the full ad is behind `redirect_url`), `redirect_url`, `created` (ISO, UTC),
  `company.display_name`, `location.display_name` and `location.area`, `salary_min`, `salary_max`,
  `salary_is_predicted` (`"0"` or `"1"`), `contract_type` (`permanent`, `contract`), `contract_time` (`full_time`,
  `part_time`), `category`. A call without credentials answers `400` (an HTML page), which shows the host and path
  exist; with a wrong key the API answers `401`.
- Countries are in the path (`gb`, `us`, `ca`, `au`, `za`, `de`, `fr`, `in`, ... about twenty markets). I did not
  find Nigeria among the markets the documentation and its wrappers list.
- `max_days_old` and `sort_by=date` are parameters of the interactive API reference, which could not be fetched;
  `sort_by` is shown in the guide (`sort_by=salary`). If Adzuna ignored either, the effect is a less recent slice, not
  an error.
- **Attribution (terms of service):** "label each displayed advert with the phrase 'Jobs by Adzuna' at least 116 x
  23 pixels in size, wherein the word 'Jobs' shall be hyperlinked to http://www.adzuna.co.uk"; every Jobsworth
  salary estimate must carry an "Adzuna Jobsworth" icon (20 x 20 px) linking to its salary predictor.
- **Commercial use:** the terms allow a 14-day trial for commercial users and require a licence agreement for
  anything beyond it, and forbid using the data "in its original format or in aggregation ... to deliver any
  ongoing work" without consent. **This is a decision for the owner of the product, not something the adapter can
  settle**; see Consequences.

### JSearch (shape from documentation and third-party summaries, not called with a key)
- A call without a key answers `401 {"message":"Invalid API key..."}` from RapidAPI, which shows the host. The same
  service is also sold directly by OpenWeb Ninja (`https://api.openwebninja.com/jsearch/search-v2`, header
  `x-api-key`); the task names RapidAPI, so that is what the adapter speaks, and the base URL, host and key are
  properties.
- Response: `{status: "OK", request_id, parameters, data: [...]}`. A job has `job_id`, `job_title`, `employer_name`,
  `job_publisher` (where it was posted: LinkedIn, Indeed, ...), `job_employment_type` (`FULLTIME`, `PARTTIME`,
  `CONTRACTOR`, `INTERN`), `job_apply_link`, `job_description`, `job_is_remote`, `job_posted_at_datetime_utc` and
  `job_posted_at_timestamp`, `job_city`, `job_state`, `job_country`, `job_location`, `job_offer_expiration_datetime_utc`,
  `job_min_salary`, `job_max_salary`, `job_salary_currency`, `job_salary_period`, `apply_options`. Different pages of
  documentation list slightly different names (`job_location` versus the city, state and country trio), so the
  mapping reads each defensively.
- It reaches Google for Jobs, so listings of LinkedIn, Indeed and others arrive without scraping them (ADR 0003 and 0004).
- **Attribution:** none was found in the public documentation. The apply link goes to the publisher, whose name
  stays in the raw posting. RapidAPI's own terms apply and were not read in full.

### Remotive (called live)
- Response: `{"0-legal-notice", "job-count", "jobs": [...]}`; a job has `id`, `url`, `title`, `company_name`,
  `company_logo`, `category`, `tags`, `job_type` (`full_time`, `part_time`, `contract`, `freelance`),
  `publication_date` (`2026-09-21T12:55:11`, no offset: read as UTC), `candidate_required_location`, `salary`
  (free text such as `$90k - $105k`, often empty), `description` (HTML). **The free feed held 16 jobs.** It is
  delayed 24 hours on purpose.
- **Attribution (legal notice in every response, README):** link back to the job's Remotive URL **and** mention
  Remotive as the source; do **not** submit Remotive jobs to third-party sites (Jooble, Neuvoo, Google Jobs,
  LinkedIn Jobs, ...); displaying the jobs to collect sign-ups or email addresses breaches the terms; breach ends API
  access. Paid access starts at USD 5,000 a month, which we do not need.
- `category` is documented in the README; it was not called with a category here.

### Arbeitnow (called live)
- Response: `{data: [...], links: {first, last, prev, next}, meta: {current_page, per_page, terms, info}}`; a job has
  `slug` (the id), `company_name`, `title`, `description` (HTML), `remote` (boolean), `url`, `tags`, `job_types`
  (`["Full-time"]`, may be empty), `location`, `created_at` (**epoch seconds**). The first two pages held 326 and 325 jobs; the real run
  below collected 937 in all. `url` is the job's page on one of Arbeitnow's country sites, or the employer's own page.
- **Attribution:** the terms require "a link back to Arbeitnow.com on your platform"; `meta.terms` asks for the
  same. The API can be revoked at any time.

### Remote OK (called live)
- Response: a JSON array. **Element 0 is not a job**: it carries `legal`, the API terms. The rest have `slug`, `id`,
  `epoch` (seconds), `date`, `company`, `company_logo`, `position`, `tags`, `description` (HTML), `location`
  (free text, often empty), `apply_url`, `url`, `salary_min` and `salary_max` (annual USD as shown on the site; **0
  means none**).
- Some titles reach us double-encoded (`MecÃ¡nico`): that is how Remote OK serves them, and the adapter does not
  repair it.
- `?tag=java` was tried and did not filter (the same 100 jobs came back), so the only target is `all`.
- **Attribution (the `legal` element):** "link back (with follow, and without nofollow!) to the URL on Remote OK and
  mention Remote OK as a source"; the Remote OK logo may not be used without written permission, the name may.

## Decision

### A target is a search, not an employer
- An aggregator target is a search: `country:query[:where]` for Adzuna (`gb:software engineer`) and
  `country:query` for JSearch (`ng:software developer jobs in Nigeria`), and `all` for Remotive, Arbeitnow and Remote
  OK (Remotive also takes a category slug). A target that does not fit is a permanent failure of that target.
- **A search has no company**: its postings name their own employers, and the normalizer already finds or creates
  the company from the posting. `SourceTargetService.addTarget` therefore takes no company for an aggregator source
  (and refuses one, because every job of the search would be filed under it); an ATS source still requires one.
- The shipped seed list (`seed-targets.json`) gets 11 aggregator targets and a starting `intervalMinutes` per
  source. The admin endpoint is **unchanged**: it still requires a company name, so it cannot add an aggregator
  search yet. Adding that is an API change (and a regenerated contract) for a later task.

### Credit is stored on the source, the listing's link on the listing
- **Migration V18** adds four nullable columns to `sources`: `attribution_name`, `attribution_text`,
  `attribution_url`, `attribution_notes`. The credit is a property of the source, not of a job, and the adapter owns
  its wording (`JobSourceAdapter.attribution()`); the registrar rewrites it at every startup, so a change in a source's
  terms is a code change. It is null for the ATS boards.
- **Each listing's own link** is `job_sources.url`, which already holds the posting's apply URL: Adzuna's
  `redirect_url` (the link the terms tell us to use), the job's page on Remotive and on Remote OK (so the "link back
  to the job" terms are met by following the stored URL), Arbeitnow's `url`, JSearch's `job_apply_link`.
- **`JobListingService.listingsOf(jobIds)`** (public, in `ingestion`) returns, per job, each listing's source, kind,
  URL and attribution. That is all the later search and job pages need to display the credit; **displaying it
  is theirs** (`attribution_notes` says how, per source: label wording, followed links only, no logo).
- **Not stored: the publisher of a JSearch job.** No terms require it, and a column would need a new field on the
  normalizer's input. `job_publisher` stays in the raw posting (kept 30 days, P6.4), so it cannot be relied on for
  display; a `job_sources.publisher` column is a one-migration addition if the UI wants it.
- **Adzuna's salary estimates are not stored.** A posting with `salary_is_predicted` other than `0` (or absent) gets
  no salary: showing an estimate needs the "Adzuna Jobsworth" label, and an estimate is not what the employer said.

### Keys, and a source without one
- Keys come from the environment through `application.yml`: `ADZUNA_APP_ID`, `ADZUNA_APP_KEY`, `JSEARCH_RAPIDAPI_KEY`
  (`app.ingestion.aggregators.adzuna.app-id`, `.app-key`, `jsearch.api-key`). `.env.example` has the three names
  with blank values, and `docker-compose.yml` passes them (blank by default). Nothing is committed that is a key;
  the properties' `toString` prints "credentials=set" or "missing", never a value.
- **A blank key does not stop startup.** The source is registered (so its attribution and targets exist and
  adding the key needs only a restart) and `JobSourceAdapter.unavailableReason()` says why it cannot run. The
  registrar logs that once, as a warning. The scheduler skips the source without recording a run (so it is not
  `FAILING` for something that is not a failure), and `IngestionService.runNow` refuses with
  `SourceUnavailableException` carrying the reason; `IngestionService.unavailableReason(code)` lets the admin
  dashboard (P2.7) show it. `fetch` also refuses, in case it is called directly.

### Quota handling, per source, in configuration
Four levers, all under `app.ingestion.aggregators.<source>` except the last, with defaults inside the free tier:

| | `max-pages-per-target` | `max-requests-per-day` | `min-request-interval` | run interval (`sources.config`) | resulting load |
|---|---|---|---|---|---|
| Adzuna | 2 (of 50 results) | 200 (limit 250) | 2.5 s (limit 25 a minute) | 720 min | 5 seeded searches x 2 pages x 2 runs = 20 calls a day |
| JSearch | 1 | 6 (200 a month is 6.5 a day) | 1 s | 1440 min | 3 searches x 1 page x 1 run = 3 calls a day, 90 a month |
| Remotive | 1 (one request) | 4 (advised maximum) | 35 s (blocked above 2 a minute) | 480 min | 3 a day |
| Arbeitnow | 5 (of about 325 jobs) | 100 | 1 s | 360 min | about 3 requests a run (the feed ended after 937 jobs), 12 a day |
| Remote OK | 1 (one request) | 24 | 5 s | 360 min | 4 a day |

- **Pages per target** bound the cost of one target in one run. The **daily budget** is a hard stop per UTC day,
  counted in memory across targets, runs and retries (`RequestGate`). The **minimum interval** paces the requests
  inside one fetch: the pipeline's rate limiter paces calls to `fetch`, not the pages inside it, so a multi-page
  source needs its own pacing. The **run interval** is the existing `intervalMinutes` of `sources.config`, seeded per
  source and changeable by an admin.
- **When the daily budget is spent:** a target that has not fetched anything fails with a permanent error that
  says so ("daily request budget (6) is used up; it resets at 00:00 UTC"), visible in the run record and not counted
  against the circuit breaker; a target that already has earlier pages keeps them and stops early (a warning is
  logged). The budget is in memory, so a restart gives it back; that is acceptable because `last_run_at` is persisted
  and a restart does not start a source early. The monthly limits (JSearch's 200, Adzuna's 2,500) are respected by the
  daily numbers, not enforced across restarts or instances; with more instances the daily numbers multiply.

### Aggregators are not full listings
Every aggregator returns `false` from `fullListing()`: each fetch is a capped slice of search results, so a posting
missing from it says nothing (the pipeline also never counts a miss for an aggregator source). A job is expired only by
the 45-day rule of ADR 0019, 45 days after any aggregator last saw it, and stays active while an ATS lists it. A test
runs a source that stops returning a job three times (still active, zero missed runs), then ages the listing.

### Fetching and failures
- The same style as ADR 0020: `RestClient` on the JDK HTTP client, timeouts and a 32 MB body cap from
  `app.ingestion.aggregators.*`, no redirects, 429/408/5xx/timeouts/resets transient, every other failure permanent.
  `401` and `403` (a rejected key) are permanent: one target's problem is not the breaker's.
- **Messages never contain a URL, a query, a header value, any response body, or a cause.** This matters more than
  for the ATS boards: Adzuna's `app_key` is in the query and JSearch's key is in a header, and the text of
  Spring's I/O exception repeats the URL, so the cause is not attached at all. A test asserts it for every adapter and every
  failure, with marker values for the keys.
- URLs are built with percent-encoding (a space is `%20`, a `+` is `%2B`, so `c++ developer` survives) and passed
  to the client as a `URI`, so nothing re-encodes them.

### What an adapter stores and maps
- The raw posting is the JSON the source returned, untouched. Adzuna's location gets the target's country appended
  (`Manchester, Greater Manchester, GB`) because Adzuna names no country; its salary currency is the country's, and
  its salary period is left unstated, so the normalizer infers a year only where the size leaves no doubt.
- Remotive and Remote OK are always remote and Arbeitnow and JSearch have a remote flag; Adzuna has none (an ad with
  a place is on-site, per ADR 0019).
- `Fields` (the null-tolerant JSON reader of the ATS package) is now public, with two additions for these sources
  (`epochSeconds` and `utcInstant`).

## Verified locally
A real run of the three keyless sources against a throwaway Postgres (a Testcontainers database, not the
development one) on 2026-10-01, through the seeded targets and the real pipeline:

| | fetched | created | updated | status | notes |
|---|---|---|---|---|---|
| Remote OK | 99 | 99 | 0 | SUCCEEDED | all remote; 15 with a salary; 33 with a country; a second run: 99 updated, 0 created |
| Arbeitnow | 937 | 918 | 19 | SUCCEEDED | 668 on-site, 166 remote, 103 hybrid; 563 with a country; 19 postings shared a fingerprint with another posting of the run and were merged into its job |
| Remotive | 16 | 15 | 1 | SUCCEEDED | all remote; 10 with a salary (from the free text) |

Adzuna and JSearch could not be run without keys: they are covered by fixtures only, and report themselves unavailable.

## Consequences
- **Adzuna's terms need a decision before the product is public.** They allow a 14-day commercial trial and ask for a
  licence after it. Until the owner decides, leaving `ADZUNA_APP_ID` blank keeps the source off; the other four
  carry no such restriction that was found (Remotive forbids passing its jobs on to other job sites, and forbids
  showing them to collect sign-ups).
- The credit is **stored and handed out, not shown**: nothing in this task renders it, so until P2.6 builds the pages,
  jobs from these sources are in the database without their credit on any screen. The search and job pages must show
  it before any aggregator is enabled for users.
- Remotive's free feed is small (16 jobs today) and 24 hours late, Remote OK returns its latest 100, and Arbeitnow is
  German. Adzuna and JSearch carry the volume (`PLAN.md`'s 20k active jobs goal), and are the two that need keys.
- The 5-page Arbeitnow cap and the 100-job Remote OK feed mean a job that is still listed but older than the slice is
  not refreshed and expires 45 days after it was last seen. That is the cost of staying inside the quotas.
- The location parser's weak spots show on aggregator data: German towns outside its city table get no country, and a
  list of countries ("USA, Canada, Argentina, Mexico, Peru") is read as one of them. Both are ADR 0019 limits, not
  adapter faults; the raw postings allow a re-run when the parser improves.
- Two sources that list the same job (an aggregator and the employer's board) are merged by fingerprint as before; the
  first to list a job owns its content (ADR 0019), so an aggregator's snippet can stay the description of a job
  until the employer's board is ingested.
- The in-memory daily budget does not coordinate several instances. One instance is the deployment for now.
- The seeded searches are a starting list for software roles in a few markets; they are `source_targets` rows and
  change without a release once an admin path for them exists.
