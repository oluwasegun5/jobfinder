# 0023. Job search

## Status
Accepted

## Context
P2.6 makes the job corpus searchable: keyword and filters, "similar jobs" by embedding, a job page, and saving or
hiding jobs, with search under 300 ms at p95 on 20,000 jobs (the Phase 2 done-when in `PLAN.md`). `PLAN.md` fixes the
indexes (GIN on `jobs.search`, HNSW on `jobs.embedding`, btree on `(status, posted_at)` and `(country, work_mode)`) and
the `user_job_actions` table, and leaves open who may search, how results are ranked and paged, how a salary filter can
work across currencies and pay periods, how "similar jobs" behaves with filters, which module reads the job tables, and
how a description may be shown. ADR 0019 left the read side of `jobs` to this task; ADR 0021 requires aggregator
credits to be displayed wherever their jobs are.

## Decision

### Module boundary
- **The `jobs` module owns search; ingestion keeps every write.** `jobs` reads `jobs`, `companies` and `job_sources`
  with its own `JdbcClient` queries (the way `embeddings` already reads job text) and owns `user_job_actions`.
  Nothing in `jobs` updates a jobs table, and `ingestion` has no read API for search to depend on. Two alternatives were
  rejected: a public read API in `ingestion` (it would grow a query language that only search needs and tie its
  release to search's), and a new `search` module (search would still need the `jobs` module's tables and the saved and
  hidden state is about jobs, so it would be a second module for one concern).
- The one thing search takes from `ingestion` is its public `JobListingService.listingsOf`, for each job's listings and
  the attribution each source requires. `jobs` also listens for `UserDeletionRequested` and removes the user's rows.
- Public API of `jobs`: none yet (controllers and services are `internal`). Spring Modulith's `verify()` passes.

### Who may search
**Signed-in users only.** `PLAN.md` section 9 scopes every query by `user_id` and the security configuration already
requires a token for everything that is not an auth or operational endpoint; hidden jobs and the saved flag are
per-user parts of every result, and an anonymous endpoint would need its own rate limiting and scraping defence (the
corpus is the product). A public, cached, user-less variant can be added later without changing these endpoints. The
page size is capped (`limit` 1 to 50, default 20; 400 beyond), the keyword at 200 characters, list filters at a few
values, and every search runs with a 3 s `statement_timeout` (503 `search_timeout`, configurable as
`app.search.statement-timeout`).

### API (all under the caller's token; the user is never a parameter)
| Endpoint | |
|---|---|
| `GET /jobs` | Search. `q`, `workMode`, `employmentType`, `seniority`, `country` (lists: repeat the parameter), `location`, `companyId`, `minSalary` + `salaryCurrency`, `postedWithinDays`, `limit`, `cursor`. |
| `GET /jobs/{id}` | The job in full, with every listing and its attribution. |
| `GET /jobs/{id}/similar` | Nearest neighbours by embedding, same filters (no `q`). |
| `PUT` / `DELETE /jobs/{id}/save`, `PUT` / `DELETE /jobs/{id}/hide` | Idempotent, 204; unknown job 404. |
| `GET /saved-jobs` | The caller's saved jobs, newest saved first. |

Only `ACTIVE` jobs are searchable; the job page and the saved list also show expired jobs (a saved job that expired
is marked by its `status`, not silently dropped). Hidden jobs are removed from the caller's search and similar-jobs
results and still open by link (that is where they are un-hidden).

### Full-text search (migration V20)
- **`jobs.search`**: weighted `tsvector` of title (A) > company name (B) > skills (C) > description (D, first 100,000
  characters). A generated column cannot read `companies`, so a **trigger** maintains it: on insert and on an update
  that really changes title, company, skills or description (embedding writes and identical refreshes keep the stored
  vector), plus a trigger on `companies.name` for renames. Ingestion needs no change. Existing rows are backfilled in
  the migration. A GIN index covers `status = 'ACTIVE'` rows only.
- **`english` configuration.** Stemming ("engineers" finds "engineer"), stop words, and `websearch_to_tsquery` syntax
  for users (quotes, `or`, `-exclude`; it never raises a syntax error). The corpus is overwhelmingly English; for the
  occasional German or French posting the English stemmer is harmless (it leaves unknown words alone), and a `simple`
  configuration would lose plurals for the majority. A query of only stop words matches nothing.
- **Ranking in two tiers.** *Tier A*, jobs matching in title, company or skills (`jobs.search_head`, the same vector
  without the description): ranked by `(0.1 + ts_rank_cd(head, query, 32)) * (0.5 + 0.5 * 0.5^(age / 30 days))`, so
  relevance decides and recency only separates jobs of similar relevance (a fresh job scores up to twice an old one of
  equal relevance, and never more than a title match gives). *Tier B*, jobs matching only in the description: listed
  after tier A, newest first, unranked. This is a measured decision: `ts_rank_cd` reads every matching document, and a
  description's tsvector is large, compressed and stored out of line. Ranking all matches on it cost 650 to 870 ms at
  p95 for a word present in most descriptions ("team"); ranking on the head only still cost 650 ms because the match
  itself had to read the full vector; splitting the tiers lets the database stop after one page for tier B and rank only
  the few thousand head matches for tier A. The price is that a description mentioning a word ten times does not rank
  above one mentioning it once; for job search the title, company and skills are the signal.
- No keyword: newest first (`sort_at` desc).
- `sort_at` is a generated column, `least(coalesce(posted_at, created_at), created_at)`: when the job was posted, else
  first seen, never later than first seen (a future-dated posting must not top every list).

### Filters
- `workMode`, `employmentType`, `seniority`, `country` (ISO alpha-2, any of the given values), `companyId` (an employer's
  jobs; the job page links to it), `postedWithinDays` (1 to 365, on `sort_at`); all given filters apply (AND).
- **`location` is a city**, matched case-insensitively and exactly (`lower(city)`), because the normalizer stores a parsed
  city and country code (ADR 0019); free-text matching of `location_raw` would need a trigram index and extension. A
  place the normalizer could not read ("Remote - EMEA") is found by `workMode` and `country`, not by `location`.
- **Salary is currency-aware and period-aware, with stated limits.** `minSalary` requires `salaryCurrency` (400
  `salary_currency_required` otherwise): amounts in different currencies are never compared (there is no exchange-rate
  source, and a guess would hide good jobs). A job qualifies when the **top of its stated range, as a yearly amount in
  its own currency, reaches `minSalary`**. Yearly amounts come from a stored generated column,
  `salary_annual_top = coalesce(salary_max, salary_min) * {HOUR 2080, DAY 260, WEEK 52, MONTH 12, YEAR 1}`. A job with no
  salary, or a salary with no stated period (the normalizer never guesses one, ADR 0019), is left out when a salary
  filter is used. `salaryCurrency` alone lists jobs stating a salary in that currency.
- Partial indexes `WHERE status = 'ACTIVE'`: `(sort_at desc, id desc)`, `(company_id, sort_at desc, id desc)`,
  `(lower(city))`, `(salary_currency, salary_annual_top)`, GIN on `search` and on `search_head`. The existing
  `(country, work_mode)` index serves the country filter.

### "Similar jobs" (semantic mode)
- The nearest active jobs to a given job by **cosine distance** on `jobs.embedding` (HNSW, `vector_cosine_ops`), nearest
  first, each with `similarity` (1 minus distance). The source may be any job that has an embedding (404 unknown, 409
  `embedding_unavailable` before it is embedded; the job page says whether it is available). Only vectors of the
  **same embedding model** as the source are compared (ADR 0022: a mixed-model index is wrong for ranking). **No
  resume matching**: that is Phase 3.
- **The list is bounded to the nearest 100** (`app.search.similar-neighbours`). Its pages are keyset slices
  `(distance, id)` of that top 100, which each page recomputes (about 10 to 45 ms, see below).
- **Filters and hidden jobs apply inside the index scan.** Plain HNSW is an approximate nearest-neighbour *search* and
  a `WHERE` is applied *afterwards* to the `ef_search` candidates (default 40), so a filtered query can come back with
  few or no rows. Measured on the 20,000-job set with an unrelated `status = 'ACTIVE'` filter alone (10% expired): the
  index returned 32 of the 100 requested with pgvector's defaults. Each similar-jobs query therefore runs in a
  transaction with `hnsw.ef_search = 100` (never below the list size) and `hnsw.iterative_scan = relaxed_order`
  (pgvector 0.8: the scan keeps going until enough rows pass the filter, up to `hnsw.max_scan_tuples`, default 20,000);
  with that the same query returns 100 of 100, all of them in the exact top 100. Rows come back approximately ordered,
  so the outer query sorts them. For very selective filters (one company, a country and a mode and a seniority) the
  planner prefers an exact plan (btree filter, then an exact sort of the few rows), which has full recall. The perf test
  asserts that filtered similar-jobs queries return everything that exists, up to a page, for 60 sources.
- A user who wants more than the nearest 100 should search by keyword; this is a recommendation widget, not a feed.

### Pagination
**Keyset, never OFFSET.** The cursor is an opaque base64url JSON document: the last row's sort key (rank score, or
`sort_at`, or distance, as exact text so a double round-trips), its id as the tie-breaker, the mode, the instant the
first page was computed (the recency half of the score is a function of "now"; fixing it in the cursor keeps ordering
stable across pages), and a digest of the query it was issued for (using it with other parameters is 400
`invalid_cursor`, as is a malformed or forged one). It is not signed: it only selects a position in the caller's own
results, with every value bound as a parameter. Order is `(key, id)` with `id` descending (ascending for distance), so
rows with equal keys are neither skipped nor repeated; tests cover ties for every mode, rows inserted or removed between
pages, and the tier boundary of keyword results. There is no total count (counting every match costs as much as the
ranking).

### Save and hide
`user_job_actions (user_id, job_id, action, created_at)`, `action` one of the four of `PLAN.md` (`VIEWED`, `SAVED`,
`HIDDEN`, `APPLIED`; only `SAVED` and `HIDDEN` are written now), primary key `(user_id, job_id, action)`, cascading
from `users` and `jobs`. **Hiding a job un-saves it and saving a hidden job un-hides it**, so a job is never both.
Every write and read is scoped by the token's user; tests show that one user's state is invisible to and untouched by
another's, that a user id in the query or body is ignored, and that deleting an account removes the rows.

### The job page and attribution
`GET /jobs/{id}` returns each listing, oldest first, with its source's `attribution` (ADR 0021: name, text, URL,
display notes) from `JobListingService.listingsOf`. **The page must display every attribution it receives**; the web
job page shows a "Listed on" section with each listing's link and credit text.

### Rendering descriptions
The API exposes **`description_text` only**, as plain text, and the web page renders it as text in a `whitespace-pre-line`
element: **no `dangerouslySetInnerHTML` anywhere**. The normalizer does guarantee that `description_html` is sanitized
(jsoup `relaxed` safelist, images removed, links forced to `rel="nofollow noopener noreferrer"` and absolute http(s)),
but a server-side guarantee is one dependency's behaviour, text needs no sanitizer in the browser at all, and the text
keeps the line structure and `- ` bullets the normalizer produces. Rich rendering can be added later with a client-side
sanitizer (DOMPurify) as a second layer. Apply and listing URLs are third-party data too: the page links only `http(s)`
URLs (never `javascript:` or `data:`), with `rel="noopener noreferrer nofollow"`.

### Performance (the done-when)
`SearchPerformanceTests` (`@Tag("perf")`, excluded from `./mvnw test`; run with `make search-perf`, i.e.
`./mvnw test -Pperf -Dtest=SearchPerformanceTests`) loads **20,000 generated jobs** into the Testcontainers Postgres: Zipf
distributed vocabulary and 2.5 KB descriptions, 600 companies, 30 cities, 40% with salaries in several currencies and
periods, 10% expired, posting dates over 60 days, **1024-dimension embeddings in 64 clusters** and the HNSW index built
after the load. A user with 1,000 hidden and 300 saved jobs makes about 1,700 requests through the real API (security
chain and JSON included) and the test fails if p95 of any query type, or of the whole mix, reaches 300 ms. Measured on a
Mac (Docker Desktop, one Postgres, default settings), first passing run:

| query type | n | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|
| keyword, one common word | 150 | 25.2 | 92.4 | 163.7 |
| keyword, several words | 150 | 8.7 | 16.9 | 30.3 |
| keyword, rare word | 100 | 33.3 | 51.8 | 75.7 |
| keyword, phrase and exclusion | 100 | 18.1 | 96.9 | 107.9 |
| newest first, no keyword | 100 | 6.4 | 15.2 | 33.1 |
| filters only | 200 | 6.8 | 8.4 | 10.9 |
| keyword and filters | 200 | 10.2 | 31.2 | 70.9 |
| similar jobs | 150 | 12.4 | 25.9 | 31.9 |
| similar jobs with filters | 150 | 43.7 | 105.7 | 128.6 |
| keyword, deep pages (10 by cursor) | 120 | 57.8 | 108.0 | 128.8 |
| newest first, deep pages | 120 | 5.9 | 6.9 | 7.5 |
| job detail | 150 | 3.4 | 10.2 | 36.9 |
| saved jobs | 50 | 4.4 | 13.6 | 23.9 |
| **whole mix** | 1740 | 11.0 | **91.7** | 108.0 |

The first run failed (p95 868 ms for a common word, p99 1,342 ms for an exclusion query) and drove the two-tier ranking and
`search_head` above; the test's threshold was not touched.

## Consequences
- Search is a hand-written, parameter-bound SQL builder over the jobs tables; each filter is one condition and one
  index. Adding a filter is a repository change plus, if it is selective, a partial index.
- Triggers keep `search` and `search_head` current without changing ingestion, at the cost of re-parsing a description
  whenever its text really changes. A language other than English would need a per-row configuration.
- Relevance is deliberately simple. A description-only match cannot outrank a title, company or skills match, and
  matches within the description tier are ordered by recency alone. Learn-to-rank from saves and hides is a later phase
  (`user_job_actions` is the signal it will use).
- The salary filter cannot find jobs whose salary has no stated period, and never converts currencies.
- Cursors are not signed; a client can only move within its own results.
- Search needs a signed-in user; a public, cached search would be a new endpoint with its own limits.
- A perf test with a latency assertion belongs to a dedicated run on a quiet machine; it is not part of CI.
- Left for later: saved searches (`saved_searches`, `PLAN.md` section 5), job views (`VIEWED`) and the application
  tracker's `APPLIED`, resume-based matching (Phase 3), and the admin source dashboard (P2.7).
