# 0003. Official and public job APIs first, scraping last

## Status
Accepted

## Context
Job aggregation is the core data supply. Sources can be reached through ATS public board APIs
(Greenhouse, Lever, Ashby, Workable, SmartRecruiters, Recruitee), aggregator APIs (Adzuna,
JSearch, Jooble, Remotive, etc.) or by scraping websites. Scraping breaks whenever markup
changes, often breaches terms of service and creates legal exposure.

## Decision
- Source priority: ATS public board APIs, then aggregator APIs, then scraping as a last resort.
- Every source is an adapter behind one `JobSourceAdapter` interface in the `ingestion` module,
  with its own rate limits, retries and circuit breaker, and a kill switch.
- Each source's current endpoint, response shape and terms are recorded in an ADR when its
  adapter is built. Attribution terms are stored and displayed.
- Scraping lives in a separate optional `scraper-worker` (Phase 3+), only for sources with no
  API and permissive terms, respecting robots.txt, identifying the bot and rate-limiting per
  domain.

## Consequences
- Ingestion is more stable and legally defensible.
- Coverage depends on which companies use supported ATSs and on aggregator quotas; some jobs
  will be missed until more adapters are added.
- Each adapter has WireMock fixture tests, so API changes show up as failing fixtures and source
  health alerts rather than silent data loss.
