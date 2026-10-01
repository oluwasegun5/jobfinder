# 0020. ATS adapters

## Status
Accepted

## Context
P2.3 adds the six ATS adapters promised by `PLAN.md` section 6 (Greenhouse, Lever, Ashby, Workable,
SmartRecruiters, Recruitee), a seed list of company board tokens, and an admin endpoint to add targets.
ADR 0003 chose official or public APIs over scraping; this ADR records what each board's public API actually
looks like today. Everything below was checked on 2026-10-01, against the vendors' documentation and by calling
each endpoint (read-only GETs of public job boards). Where the documentation and the live responses differ, the
live response is what the adapter follows, and the difference is noted.

## What each board offers

None of the six needs a key or a token to read a job board. Only Greenhouse, Lever and SmartRecruiters document
a rate limit, and none of those limits applies to the read calls we make. In practice Workable's host rate
limits hard (see below), so the limit is a property of the host, not of the documentation.

| | Endpoint (all `GET`) | Pagination | Notes |
|---|---|---|---|
| Greenhouse | `https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true` | none: every published job in one response (714 jobs, 5.5 MB for the largest board we sampled) | Documented as public, no auth (only the application `POST` needs Basic auth). `content` is the HTML description, **escaped inside the JSON string**. Fields used: `id`, `title`, `company_name`, `location.name`, `absolute_url`, `first_published`, `content`, `application_deadline`, `pay_input_ranges`. Unknown token: 404. |
| Lever | `https://api.lever.co/v0/postings/{site}?mode=json&skip=N&limit=N` | `skip` / `limit`; a bare JSON array, no total, so we page until a short page | Documented 2 requests per second applies to the application `POST`, not to reading. The EU instance (`api.eu.lever.co`) is a different host and is **not supported yet**. The description is split over `description`, `lists[]` (heading plus `<li>` items) and `additional`; salary is in `salaryRange {min, max, currency, interval}` when the posting has one; `workplaceType` is `remote`, `onsite`, `hybrid` or `unspecified`; `createdAt` is epoch milliseconds. Unknown site: 404. |
| Ashby | `https://api.ashbyhq.com/posting-api/job-board/{name}?includeCompensation=true` | none: every listed job in one response | No rate limit documented. `isListed` can be false; `isRemote` and `workplaceType` (`Remote`, `Hybrid`, `OnSite`); `employmentType` (`FullTime`, `PartTime`, `Contract`, `Intern`, `Temporary`); `descriptionHtml`; `compensation.summaryComponents[]` with `compensationType` (`Salary`, `Bonus`, `EquityPercentage`), `interval` (`1 YEAR`), `currencyCode`, `minValue`, `maxValue`. Unknown name: 404. |
| Workable | `https://apply.workable.com/api/v1/widget/accounts/{subdomain}?details=true` | none: every published job in one response, descriptions included with `details=true` | Workable's help centre documents `www.workable.com/api/accounts/{subdomain}?details=true` as the public careers-page endpoint; it answers `302` to the URL above, which the adapter calls directly. The account API (`/spi/v3/jobs`) needs a bearer token owned by the account, so it is not usable. Job fields: `shortcode` (the id), `title`, `employment_type`, `telecommuting`, `city`, `state`, `country`, `url`, `published_on`, `description`; the company name is at the top of the response (`name`). **An unknown subdomain is either 404 or `200` with no jobs**, so "resolves" has to mean "has at least one job". **Cloudflare rate limits this host hard** (HTTP 429, error 1015): it blocked us after a few requests per second and again after about 20 requests at 0.7 per second. |
| SmartRecruiters | `https://api.smartrecruiters.com/v1/companies/{id}/postings?limit=100&offset=N`, then `.../postings/{postingId}` per posting | `limit` (max 100) and `offset`; `totalFound` in the response | Public postings need no auth (the API key or OAuth only unlock internal postings). Documented limits: 10 requests per second and 8 concurrent, `429` beyond that, `X-RateLimit-*` headers. **The list has no description**: it is in the detail's `jobAd.sections` (`companyDescription`, `jobDescription`, `qualifications`, `additionalInformation`), so a full fetch is one request per posting. **An unknown company id is `200` with `totalFound: 0`**, not 404. Fields: `name`, `company.name`, `releasedDate`, `location {city, region, country, remote, hybrid, fullLocation}`, `typeOfEmployment.label`, `postingUrl`, `applyUrl`. |
| Recruitee | `https://{company}.recruitee.com/api/offers/` | none: every published offer, descriptions included | Documented as "does not require authorization" (the token-gated admin API is a different product). Fields: `id`, `title`, `company_name`, `description` and `requirements` (HTML), `location`, `city`, `country`, `remote`, `hybrid`, `on_site`, `employment_type_code`, `careers_url`, `published_at` (`2026-09-29 16:02:37 UTC`), `close_at`, `salary {min, max, period, currency}` (usually null), `status`. Unknown company: 404 with a JSON body. |

Sub-fields of Greenhouse's `pay_input_ranges` (`min_cents`, `max_cents`, `currency_type`) follow Greenhouse's
published description of pay transparency. None of the ten large boards we sampled had the field filled in, so
that mapping is covered by a fixture, not by a live sample.

## Decision

### One HTTP style, one place that classifies failures
- **`RestClient` on the JDK HTTP client**, built like `AiServiceResumeParser`, with connect and read timeouts from
  `app.ingestion.ats.*`. All six adapters call through `AtsHttp`, which is the only code that decides what a
  failure means to the pipeline (ADR 0018):
  - **transient** (retried, counted by the circuit breaker): timeouts, connection errors, `408`, `429`, `5xx`;
  - **permanent** (not retried, not counted): any other non-2xx status (a `404` for a retired board), a
    redirect (not followed: the hosts are fixed and a redirect is unexpected), a body that is not JSON, a body of
    the wrong shape (no `jobs` / `offers` / `content` list), a body over 32 MB, and a board token that is not a
    plain slug.
- **Messages carry the source and the board token, never a URL, a query, a header or any of the response body.**
  Nothing here is a credential (the boards are public), but the rule of ADR 0018 holds without exception, and a
  test asserts it for every adapter and every failure.
- **Board tokens are validated before they are used**: letters, digits, `.`, `_`, `-`, at most 100 characters,
  and for Recruitee (where the token becomes a host name) a single DNS label. A token with a slash, a space or
  a `#` can never change the path or the host of the request.
- **Redirects are not followed**, and no `Accept-Encoding` is sent, so a response is read as it is, bounded.

### What an adapter stores and maps
- **The raw posting is the JSON the board returned**, untouched, so it can be reprocessed when normalization
  improves (ADR 0018). Two additions, both facts the response puts outside the posting: Workable's account name
  is copied into each posting as `company_name`, and a SmartRecruiters posting is stored as its detail when one
  was fetched.
- **`toNormalizerInput` only puts each fact in the right field**; parsing stays in the normalizer (ADR 0019).
  Dates become instants (Workable's bare dates are midnight UTC). Descriptions are passed as received: Greenhouse's
  escaped HTML is unescaped by the normalizer, Lever's three parts are put back together, SmartRecruiters' four
  sections get their own headings.
- **Hybrid** cannot be said with a yes/no remote flag, so a hybrid posting leaves `remote` null and appends
  "(Hybrid)" to the location text, where the normalizer reads it. A source flag of `false` would otherwise win
  over the words and turn a hybrid job on-site.
- **Salary** is passed only where the board states it as numbers (Greenhouse, Lever, Ashby, Recruitee); the
  period is the board's own word (`per-year-salary`, `1 YEAR`, `month`), which the salary parser reads.
- **Every ATS is a full listing** (the default of `fullListing()`): each fetch returns everything the board
  lists, `since` is ignored, and a job that disappears from the board counts towards the two-run expiry rule.
  Ashby's unlisted jobs and Recruitee's non-published offers are left out of the fetch for that reason: they are
  not on the board.
- **Company**: the target names its company (below), and a target's company wins over what a posting says
  (ADR 0019). `company_name` in the payload is only a fallback.

### SmartRecruiters: bounded detail requests
Fetching every posting's detail is one request per posting, and a board can have thousands (one seeded board has
more than 4,000). The adapter fetches at most `smart-recruiters-max-details` (default 100) details per target per
run, one at a time with `smart-recruiters-detail-delay` (default 150 ms, which keeps it under the documented 10
requests per second). A posting past the bound, or whose detail returned a permanent error (it closed in between),
is stored from its list entry: it becomes a job without a description. A transient failure on a detail fails the
fetch, as any other transient failure does. This is a deliberate cost cap, not an oversight: raising the bound is
a property change.

### Rate limiting and Workable
The pipeline limits requests per source (2 per second by default, per `sources.config`). That is right for
five of the hosts and wrong for Workable, so the seed file carries a starting tuning for it
(`requestsPerSecond: 0.2`, one board every five seconds). The seed loader applies a source's tuning only to keys
the source has not set, so an admin's own value is never replaced. A `429` from Workable is still transient and is
retried with backoff.

### Adding targets
- **`POST /admin/ingestion/targets`** takes `{source, identifier, companyName}`, creates the target (and finds or
  creates the company by normalized name, as the normalizer does) in one transaction, and answers `201`. Posting
  the same target again answers `200` with the existing one and changes nothing: an admin's decision to disable a
  target is never undone, and its company is kept. Unknown source: `404` (`unknown_source`); unusable identifier or
  company name: `400` (`invalid_target`); a missing or blank field: `400` (`validation_failed`).
- **The company name is required**, because Lever and Ashby do not say who the employer is, and a posting with no
  company is rejected (ADR 0019).
- **Security**: the first role-gated endpoints of the API. `SecurityConfig` requires `ROLE_ADMIN` for `/admin/**`
  (the access token's `role` claim becomes the authority), so a new admin endpoint is protected by being under the
  prefix, not by remembering an annotation. A signed-in non-admin gets `403`; no token gets `401`. The endpoint
  touches no user data, so it has no ownership test; it has an authorization test instead.
- **The module boundary**: the endpoint lives in `admin` and calls the ingestion module's new public
  `SourceTargetService`. `ingestion` does not know `admin`.

### The seed list
- **`src/main/resources/ingestion/seed-targets.json`** is data: `{sources, targets}`, where each target is
  `{source, token, company}`. It holds public board tokens and company names, nothing personal.
- **It is loaded on startup** (`app.ingestion.seed.enabled`, default true) and only adds what is missing. A target
  that exists, enabled or not, is left alone, so restarts are cheap and an admin's changes survive. One bad entry
  is logged and skipped. Tests turn seeding off in `src/test/resources/application.properties`, so no test context
  schedules runs against real boards.
- **How the tokens were chosen and checked**: candidate company names (tech companies that hire remotely, and
  companies that hire in Africa) were tried against each board's endpoint, and a token was kept only if the board
  answered `200` with at least one open job on 2026-10-01. Nothing was added from memory without that call. Of the
  matches, 27 were then removed because the token turned out to belong to a different company or to a
  test board (for example a board called "Sterling Brands" under the token `sterling`, or a board whose only job
  was "Test UAT"). Company names come from the board itself where it states one (Greenhouse's board name,
  SmartRecruiters, Recruitee, Workable) and were written by hand for Lever and Ashby, which do not.
- **Result: 540 company names tried, 297 matches found, 270 targets in the seed**
  (Greenhouse 134, Ashby 94, Lever 20, SmartRecruiters 13, Workable 7, Recruitee 2). Workable and Recruitee are thin because most of the names tried are companies on the other
  boards, and Workable's host stopped answering during the check, so only about 60 names were tried against it.
- **"Resolves" is not "is the company we meant".** Tokens are slugs chosen by each employer, so a verified token
  can in principle be a namesake. The list was read once by hand; it is a starting list, and the admin endpoint is
  how it is corrected.

## Consequences
- Each adapter is one class and a fixture test; they inherit scheduling, locking, retry, rate limiting, the
  breaker, raw storage, normalization and expiry from ADR 0018 and 0019. 270 targets means each source's
  run makes one or more calls per board every six hours (the default interval); the largest Greenhouse boards are
  several megabytes, which the response cap and the per-source limit keep in check.
- Seeded Workable and SmartRecruiters boards will occasionally answer `429`; that costs a retry, and if it
  repeated, the source's breaker would open for five minutes without touching the other sources.
- Lever's EU instance, Workable discovery and refreshing the seed list are not built. The seed will go stale as
  companies move ATS (one verified Lever board already answers with a single "we have moved" posting; it was
  dropped); the run counters and source health of P2.7 are where that will show.
- The contract (`packages/api-contract`) now includes the admin endpoint; the web app does not use it yet.
- No migration: `source_targets` and `sources.config` already hold everything this task needs.
