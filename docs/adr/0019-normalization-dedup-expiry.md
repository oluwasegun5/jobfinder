# 0019. Normalization, deduplication and expiry

## Status
Accepted

## Context
P2.2 turns the raw postings stored by P2.1 into `jobs`. `PLAN.md` section 6 fixes the steps (clean HTML, parse
location, infer work mode, employment type and seniority, normalize salary; fingerprint of company, title and
location; merge duplicates into `job_sources`; expire jobs missing for 2 consecutive runs, and aggregator jobs
unrefreshed for 45 days) but leaves open the value sets, what each rule does with an ambiguous posting, how a
source hands its fields to the normalizer, what "merge" means for the fields two sources disagree on, and how the
two expiry rules apply to a job several sources list. ADR 0018 also left the run counters, the `CHECK`
constraints on `jobs` and the company matching to this task.

## Decision

### The mapping seam
- **`NormalizerInput`** (public, in `ingestion`) is what an adapter extracts from one raw posting: title,
  company name, description (HTML, escaped HTML or text), free-text location, the source's remote flag, its
  employment-type label, structured or free-text salary, apply URL, posted and expiry dates. Nothing in it has to
  be tidy; every field but `title` may be null.
- **`JobSourceAdapter.toNormalizerInput(posting, target)`** is a default method returning empty, so an adapter not
  yet wired to the normalizer still stores raw postings and produces no jobs. An adapter may throw
  `IllegalArgumentException` for a posting it cannot read. **`fullListing()`** (default true) says whether each
  fetch returns everything the target lists; an incremental adapter must return false (see Expiry).
- The normalizer, stores and pipeline wiring live in `ingestion.internal`. `jobs` and `companies` are written by
  `ingestion` for now, with `JdbcClient`, as the other ingestion tables are. When the `jobs` module gets its read
  side (P2.6), it reads these tables through its own queries; nothing here exposes them.

### Normalization (deterministic rules only; an LLM pass for what the rules cannot read is a later step)
- **Never guess a value the text does not support.** A field the rules cannot read is stored null, with two
  deliberate exceptions below.
- **HTML to text** is parsed with jsoup, never pattern-matched. Escaped HTML (Greenhouse sends `&lt;p&gt;`) is
  unescaped once. Lists become `- ` lines, paragraphs and rows become lines, scripts and styles are dropped.
  `description_html` is **sanitized** (jsoup `relaxed` safelist, images removed, links forced to
  `rel="nofollow noopener noreferrer"`, only absolute http(s) links) before it is stored, because posting content
  is untrusted (`PLAN.md` section 9) and the web app will render it. A plain-text description has no HTML.
- **Location** is table-driven: ISO 3166 alpha-2 country codes (a built-in table of about 95 countries and their
  names and aliases), US states and Canadian provinces, and about 50 unambiguous cities. Remote/hybrid/on-site
  words are removed from the text and reported separately. A text naming several places takes the first. A
  two-letter trailing part that is also a US state is read as the state when a city precedes it, unless the city
  is a known city of another country ("Berlin, DE" is Germany; "London, ON" is Canada). An unknown city is kept
  as the city with no country.
- **Work mode** (`REMOTE`, `HYBRID`, `ONSITE`): the source's flag, then the location and title; the description
  is read only for unmistakable statements ("fully remote", "this role is hybrid"), because "remote teams" and
  "onsite gym" appear in postings of every kind. Hybrid beats remote when both appear in the location or title.
  Exception 1: a posting with a place and no signal at all is `ONSITE`. No place and no signal is null.
- **Employment type** (`FULL_TIME`, `PART_TIME`, `CONTRACT`, `TEMPORARY`, `INTERNSHIP`): the source's label, then
  title keywords, then an explicit "Employment type: ..." line. No default: null when unknown.
- **Seniority** uses the set profiles already use (`INTERN`, `JUNIOR`, `MID`, `SENIOR`, `LEAD`, `EXECUTIVE`) so
  matching compares them directly. From the title only (a senior role's description mentions juniors). Strongest
  word wins: intern, then executive (director, VP, head of, chief), lead (lead, staff engineer, principal,
  engineering/team/sales manager, senior manager), senior (including "III"), mid ("II", mid-level), junior
  (entry level, graduate). Generic "manager" titles (product, project, account, program manager) say nothing
  about seniority, because those managers are individual contributors. Null when the title has no signal;
  "Software Engineer" is not guessed to be mid-level.
- **Salary** is kept in the period the source stated (a monthly figure stays monthly) with an ISO currency. A
  salary is dropped, not guessed, when no currency can be found (an adapter whose source leaves it implicit
  states it from the country), when an amount is not positive or absurd (at least 100 billion), or when there is
  no number. `min > max` is swapped to satisfy the table's check. "Up to X" is a max only, "from X" and "X+" a min
  only. Exception 2: when the period is not stated it is inferred only where the magnitude leaves no doubt (10,000
  or more is a year, 500 or less is an hour), otherwise null. `$` is USD unless prefixed (`C$`, `A$`, `NZ$`, ...).
- **Rejected postings.** A posting with no title, no company (and no company on its target), or whose adapter
  throws on it, is *rejected on its own*: it stays in `raw_job_postings` so it can be reprocessed, produces no
  job, is logged and counted in the `ingestion.postings{outcome=rejected}` metric, and the run goes on. It is not
  a target error and does not affect run status or source health.
- The value sets are now enforced by V17 `CHECK` constraints on `work_mode`, `employment_type`, `seniority` and
  `salary_period`. The tables were empty, so this cannot fail on existing data.

### Companies
- A company is found by **normalized name** (lower case, accents removed, `&` as "and", bracketed text and legal
  suffixes such as Inc, LLC, Ltd, GmbH, Corp, Co, and a leading "The" removed): "Acme, Inc.", "ACME Corp" and
  "The Acme Company" are one company. Find-or-create takes a transaction-scoped advisory lock on the name,
  because `companies.normalized_name` still has no unique index (ADR 0018) and two sources may meet the same new
  employer at once. Two genuinely different companies that normalize to the same name are merged; that is the
  price of not asking a human, and the name is the only key an aggregator gives us.
- A target that names its company wins over what the posting says, so one board's jobs are never split over the
  spellings in its postings.

### Fingerprint and merge
- **Fingerprint** = SHA-256 of normalized company, normalized title and a location key, newline-separated. The
  title form drops decoration (`(m/f/d)`, `- Remote`, `[REQ-1042]`, `#4521`, a trailing "in Lagos" when the city is
  known) and expands `Sr`/`Jr`; it keeps the level and any specialty, so "Senior Engineer" and "Junior Engineer",
  or "Engineer (Platform)" and "Engineer (Payments)", are different jobs. The location key is `city|country`; a
  remote job without a city is placed by country alone (`remote|US`), so "Remote - US" and "United States
  (Remote)" meet.
- **Merge policy.** The first listing of a job is its *owner*. The owner's refreshes overwrite the job's
  content, reactivate it and may change its fingerprint. Any other listing only *fills* fields the job lacks
  (never replaces) and keeps it active. So an aggregator that re-lists an ATS job can add a missing salary but
  cannot rewrite the employer's own title or description. The first source to see a job owns it; there is no
  source ranking yet.
- **A listing that changes identity** (retitled, relocated) updates its job in place when nothing else lists it;
  otherwise it moves: to the job that now has its fingerprint, or to a new job. A job left with no listing by a
  move is expired.
- Within one run, two listings with the same fingerprint (one source posting a role under two requisition ids)
  share one job.
- `job_sources` gains `target_id`, `last_seen_at` and `missed_runs` (V17): the record of "this source listed this
  job", which is what expiry needs.
- Embedding-based near-duplicate merging (cosine over 0.97) is the nightly pass of P2.5, not here.

### Expiry
- **A listing is live** while (a) its source is not an aggregator and has missed it fewer than 2 times in a row,
  or (b) its source is an aggregator and last saw it within 45 days. Both are configuration
  (`app.ingestion.expiry.missed-runs`, `aggregator-stale-after`). **A job expires when it has no live listing
  left**, so a job listed by an ATS and an aggregator stays active while either lists it.
- `PLAN.md` states the two rules without saying which sources each covers. Applying "missing for 2 runs" to an
  aggregator would expire live jobs whenever its search results rotate, and make the 45-day rule redundant, so
  the missed-run rule applies to ATS and scrape sources (full listings) and the 45-day rule to aggregators.
- **A miss is counted only when the fetch succeeded**: after a target's postings are stored, in the same
  transaction, its listings not seen in this run get `missed_runs + 1`. A failed target, or a source that is not
  a `fullListing()` (an incremental adapter), never counts a miss, so an outage cannot expire a board. A listing
  seen again resets its count to zero. An expired job whose listing is seen again is reactivated.
- A job whose own `expires_at` (the source's valid-through date) has passed expires too, and a posting that
  arrives already past its date is stored as expired. `PLAN.md` does not require this; the column exists and the
  source said so.
- The expiry statement runs once per run, after all targets, for the jobs the running source lists. If it fails
  the error is logged and the run is still recorded (`expired` 0): a run's record must not be lost to it.
- A source that returns nothing for a target counts as a miss for every listing of that target; the 2-run rule is
  the guard against one glitchy empty response. The alert on a source returning 0 jobs is P2.7.

### Run counters (ADR 0018 pointed them here)
`created` is **new jobs**, `updated` is existing jobs refreshed **or merged into** as another listing, `expired`
is jobs the expiry rules closed. `fetched` is still postings received. Raw postings are stored as before, but
no longer counted separately; the existing pipeline tests were changed only where they assumed the raw meaning
(their fake source now maps its postings, and cleans up jobs). Postings of an adapter with no mapping yet count
as `fetched` only. Micrometer `ingestion.postings` gains the outcomes `expired` and `rejected`.

## Consequences
- ATS and aggregator adapters (P2.3, P2.4) add one mapping method each beside `fetch`, and a WireMock fixture
  test; they inherit normalization, dedup, merge and expiry.
- Rules are deterministic and readable, so a wrong parse is a one-line fix plus a test row, and raw postings
  allow a re-run when rules improve. Their weak spots are known: the city and country tables are small (an
  unlisted city has no country, which also weakens its fingerprint), and seniority from titles is blunt.
- A job's identity is its fingerprint, so two different openings with the same title, company and city are one
  job, and a posting that changes city becomes a different job (or moves). Both are accepted for a job *board*.
- The first source to list a job owns its content. If an aggregator lists a job before the employer's ATS board
  is ingested, the aggregator's wording stays (the ATS listing only fills gaps). Ranking sources by tier is a
  possible later refinement.
- Companies that normalize alike are merged and never split automatically.
- Nothing here is user data, so there is no `UserDeletionRequested` handler.
