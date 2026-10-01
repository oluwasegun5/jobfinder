# 0026. Matching engine: filter, recall, re-rank, cache

## Status
Accepted

## Context
P3.2 (`PLAN.md` section 7, "Matching"). Jobs have embeddings (ADR 0022) and can be searched and filtered (ADR 0023);
resumes are parsed and embedded; every AI call is billed against a daily cap (ADR 0025). What is missing is the
answer to "how well does this job fit me": ranked jobs for a user, and for one opened job a score with reasons.

A model reading every job for every user is far too expensive, and vector similarity alone is too coarse to show as
a score. `PLAN.md` fixes the shape (SQL filters, then vector recall blended with skill overlap and recency, then an
LLM re-rank of the best 30, cached in `match_scores`) and leaves open: what counts as a filter match when data is
missing, what the blend is exactly, what a cache entry is valid for, what the user sees when the model cannot be
used, how the nightly run is bounded, and how the result is checked without a real model key.

## Decision

### Three stages, one `matching` module
`matching` has a small public API (`MatchService`: `matchJob(userId, jobId)` and `rankedMatches(userId)`, with
`MatchResult`, `RankedMatches`, `MatchStatus`, `FallbackReason`) and an `internal` package. It owns no foreign tables:
it reads other modules through four new narrow interfaces, so Modulith sees no cycle and no module reaches into
another's SQL.

| Interface | Module | What matching reads |
|---|---|---|
| `CandidateProfiles` | `profile` | the latest parsed version of the primary resume, seniority, years, saved preferences |
| `ResumeEmbeddings` | `embeddings` | the current resume vector, or nothing while it is missing or stale |
| `JobMatchSource` | `jobs` | stage 1 and stage 2 recall in one query; job content; similarity of one job |
| `UserActivity` | `identity` | ids of users who signed in since an instant (the nightly run) |

**Stage 1, SQL filters from preferences** (`PreferenceFilters`, run inside `JobMatchSource.recall`):

- *Unset means no filter.* A user with no preferences row is matched against every job that qualifies below.
- *Unknown is not a mismatch.* A job with no value for the field a filter reads passes that filter. The normalizer
  leaves fields empty when it cannot tell (ADR 0019) and most postings state no salary; hiding them would hide most of
  the market. Ranking, not hiding, deals with them.
- Work modes: the job's mode is one of the preferred. Locations (free text): an entry is read as a city, as a country
  (English name or ISO code) and as a phrase in the raw location; remote jobs always pass the location filter.
- Salary floor: rules out only a job stating a *yearly* top salary *in the floor's currency* below the floor. There is
  no exchange rate, and a period is never guessed (ADR 0019), so anything else passes.
- Seniority: the candidate's level plus or minus `seniority-band` (default 1) steps of
  INTERN, JUNIOR, MID, SENIOR, LEAD, EXECUTIVE. Excluded companies (name or normalized name) and excluded industries
  (where the company's industry is known). Sponsorship is not applied: jobs carry no attribute for it.
- Never pass, whatever the preferences: hidden by this user, status not ACTIVE, `expires_at` in the past, no embedding
  of the resume's model.

**Stage 2, recall and blend.** The same query orders the passing jobs by cosine distance to the resume vector (HNSW with
`hnsw.iterative_scan = relaxed_order`, as "similar jobs" does, so a selective filter still returns up to the limit;
an exact sort by distance then id on the outer query) and keeps `recall-limit` (300). Each is then blended:

```
stage2 = 100 * (w_vector * cosine + w_skills * overlap + w_recency * recency) / (sum of the known weights)
cosine  = cosine similarity clamped to 0..1               (unknown: no embedding)
overlap = share of the job's distinct listed skills that the resume lists, case/space-insensitive  (unknown: either side lists none)
recency = 0.5 ^ (age / half-life), age from the posting time, a future date counts as new
```

Weights are configuration (`app.matching.weights`, default 0.6 / 0.3 / 0.1, startup fails unless each is in 0..1 and
they sum to 1), as are the half-life (21 days) and the limits. An unknown component leaves the blend and the others are
rescaled, so a job without skills is not punished for it. Recency is always known. The blend is pure and deterministic
(`Stage2Scorer`) and is checked by golden cases shared with the eval's Python mirror
(`src/test/resources/matching/stage2-golden.json`).

**Stage 3, model re-rank of the top 30** (`rerank-top`, of the stage-2 order; ties by recency then id) through
ai-service `POST /v1/score-matches`, in requests of `llm.batch-size` (10) jobs. The candidate sent is a compact
snapshot of the resume (headline, summary, seniority, years, titles, skills, roles, education, certifications) with
**no contact details or links**; each job is title, company, location, mode, type, seniority, salary, skills and a
description cut to `description-chars`. Results are model-scored jobs first by model score, then the rest by stage-2.

### The ai-service endpoint and prompt v1
`POST /v1/score-matches {user_id, prompt_version, candidate, jobs[1..50]}` returns per job
`{job_id, status: scored|failed, score 0..100, strengths[<=5], gaps[<=5], error_code}`, plus `model` and `usage[]`.

- Prompt `app/prompts/match_scoring/v1.md`: the model returns JSON `{"results":[{job_id, score, strengths, gaps}]}`
  and nothing else; score bands are defined in the prompt so scores mean the same thing across runs. The candidate and
  postings are untrusted text in nonce-delimited blocks (lookalike markers defused), the same trust boundary as CV
  parsing. core-api pins the version in each request and verifies the response names it, so scores made with another
  prompt are never stored under this one's key.
- Strict Pydantic validation of request and model output. A malformed answer is retried once (the shared
  `generate_structured`), then the *jobs of that call* come back `failed` (`llm_output_invalid`, `llm_refused`,
  `llm_unavailable`, `llm_output_incomplete` for a job the model skipped). One bad job never fails the request.
- Batching and budget: a request is split into calls of at most `SCORE_MATCHES_MAX_JOBS_PER_CALL` (6) jobs and
  `SCORE_MATCHES_MAX_INPUT_CHARS` (24,000, about 6k tokens) of input; one call is one `usage` record.
- `usage` is returned for every call, failed ones too, so core-api bills them (below).

### Fake provider (keyless runs)
`LLM_PROVIDER=fake` selects `HeuristicProvider`: a deterministic keyword rule (70% skill coverage, 30% title overlap)
over the same data blocks a real model reads. It only answers `match_scoring` (any other feature is a configuration
error, so CV parsing cannot silently run on it), and every record it produces says provider `fake`, model
`fake-heuristic-v1`, cost 0; startup logs a warning. The default stays `anthropic`; nothing selects `fake` implicitly.
Its scores are a plausible shape for exercising caching, the cap, fallbacks and the eval, not a judgement of fit.

### Billing and the cap
Before each ai-service request core-api calls `AiUsageGate.requireAllowance(user, "match_scoring")`; ai-service's
`usage[]` is recorded through `AiUsageLedger.record`, idempotent on `ai-service:<call_id>`, under feature
`match_scoring`, with status `FAILED` when the response (or an error body) carried usage but no job was scored.
When the daily cap is reached, matching **does not fail**: the remaining jobs are served with their stage-2 score,
`status = NOT_LLM_SCORED`, `reason = DAILY_CAP_REACHED`, and cached model scores are still served. If ai-service is
unreachable or answers an error, the same fallback applies with `reason = LLM_UNAVAILABLE`, and the run stops asking
(no retry storm). A job the model failed on is `status = UNRANKED`, `reason = LLM_FAILED`, with its stage-2 score; it
is not cached, so the next run asks again. An expired job gets `NOT_LLM_SCORED`/`JOB_EXPIRED` and is never sent.
`MatchResult.score` is always 0..100, but only `LLM_SCORED` means the model said it; a UI must check `status`.

### Cache and invalidation (`match_scores`, migration `V23__matching_engine.sql`)
Key `(resume_version_id, job_id, prompt_version)`, unique. Beside the score it stores the stage-2 components at scoring
time, strengths, gaps, model, and two hashes: SHA-256 of the exact resume snapshot and job snapshot sent to the model.
A row is served only while **both hashes equal the current ones**. So:

- *A new primary resume version* is a new key: it is scored afresh and never sees the old version's scores. Old rows are
  kept (not deleted by the edit) and pruned by retention.
- *Editing the current version in place* changes the resume hash: the row is stale, rewritten on the next scoring.
- *A new prompt version* (`app.matching.prompt-version`) is a new key.
- *A job whose content changes* (title, company, location, mode, type, seniority, salary, skills, description up to the
  cut) changes the job hash: that job alone is re-scored. A change to fields the model never sees (apply URL, status
  bookkeeping) does not. A cached score is still served after its job expires (the content has not changed); an expired job with no valid cached score is
  not sent to the model. A job deleted from the table cascades its rows away.
- The cache lookup comes before any ai-service call, and the cap check comes after it: a second call with nothing
  changed makes **zero** ai-service requests (tested), including when the user is capped.

Only model-scored jobs are stored. Stage-2 fallbacks are recomputed on every read (cheap). The resume vector counts as
current only if `EmbeddingService` agrees its input hash matches the content, so an unchanged re-save does not block
matching and a stale vector (`resume_embedding_pending`, 409) is never used.

Retention runs at the end of each nightly run: rows older than `retention.superseded-after` (14 days) that the same
user has since been scored past with a newer resume version, and any row older than `retention.max-age` (90 days).

### On demand
`GET /jobs/{id}/match` (the caller is the token's user) calls `MatchService.matchJob`: the job is scored whatever the
user's filters say (they opened it), through the same cache, cap and fallback path; a job without an embedding is scored
with the similarity left out of the blend. 404 `job_not_found`; 409 `resume_required` without a primary resume with parsed
content. The response carries `status`, `reason`, `score`, `stage2Score`, `llmScore`, `strengths`, `gaps`, `model`,
`promptVersion`, `scoredAt`. The feed that lists ranked jobs is P3.3 and reads `rankedMatches`.

### Nightly batch
`MatchBatchScheduler` (cron `app.matching.batch.cron`, default `0 30 2 * * *` UTC; `app.matching.batch.enabled=false`
turns it off) runs `MatchBatchService` under the ShedLock lock `matching:nightly` (the `shedlock` table and provider
ingestion uses): one instance at a time, `lock-at-most-for` 2h.

- *Active* means: signed in or renewed a session within `active-within` (14 days; a refresh token was issued), account
  ACTIVE and not deleted, **and** a primary resume with parsed content, saved preferences and a current resume
  embedding. A user missing the last three is *skipped*, not failed.
- Users go in id order, a page at a time. A user whose matching throws is counted as failed, logged with the user id and
  no personal data, and the run moves on (tested).
- Budget guard: the run stops *starting* users after `max-users-per-run` (500) or `max-ai-requests-per-run` (3,000)
  ai-service requests, whichever first, records `STOPPED` and the reason, and the next night starts over (cached scores
  make that cheap). Each user's own daily cap still applies; a capped user is counted `capped`.
- One row per run in `match_batch_runs` (counts, status, stop reason, rows pruned) and meters
  `matching.batch.users{outcome=matched|skipped|failed}`, `matching.batch.duration`, `matching.scores{outcome=...}` and
  `matching.ai.requests`.

### Eval (`make match-eval`)
`services/ai-service/evals/match_eval.py` runs the three stages over three invented candidates and 48 invented jobs
(`evals/fixtures/match/`, no real personal data; two jobs are edge cases: one lists no skills, one tries to steer the
model). Stage 2 is a Python mirror of the Java blend (checked against the shared golden cases) with a hashed
bag-of-words embedding; stage 3 is the service's own scoring with the chosen provider, over the top 30 per candidate.
It prints per-stage histograms and percentiles, the Spearman rank correlation between stage 2 and stage 3, and the
share unranked. The default is the fake provider, so it needs no key; `--provider anthropic` (needs
`ANTHROPIC_API_KEY`) and `--embedding voyage` (needs `VOYAGE_API_KEY`) run it against the real services. It is not part
of CI.

Sample output of `make match-eval` (fake provider, hashed vectors; deterministic, so a change in these numbers
means the blend, the fixtures or the heuristic changed):

```text
match-eval: 3 candidates x 48 jobs; stage 3 = fake (fake-heuristic-v1), stage 2 vectors = hashed

stage 2 (recall blend), all jobs: n=144
  mean=21.0  p10=8.5  p25=10.8  p50=14.9  p75=22.1  p90=51.0
    0-9     32  ###################
   10-19    68  ########################################
   20-29    18  ###########
   30-39     5  ###
   40-49     4  ##
   50-59    12  #######
   60-69     4  ##
   70-79     1  #
   80-89     0  
   90-100    0  

stage 3 (model): n=90
  mean=37.29  p10=10.0  p25=19.0  p50=28.5  p75=55.0  p90=79.1
    0-9      0  
   10-19    26  ########################################
   20-29    20  ###############################
   30-39    15  #######################
   40-49     5  ########
   50-59     5  ########
   60-69     6  #########
   70-79     4  ######
   80-89     7  ###########
   90-100    2  ###

rank correlation stage 2 vs stage 3 (Spearman, scored jobs): 0.877
  backend-senior     scored 30/30  rho=0.853
  data-mid           scored 30/30  rho=0.788
  frontend-junior    scored 30/30  rho=0.878
unranked: 0/90 = 0.0%
llm: 18 call(s), 17689 in / 3275 out tokens, cost $0

```

How to read it: stage 2 piles up low because most of the 48 jobs are from other families and the hashed vectors only
see shared wording; the heuristic stage 3 spreads wider and ranks the same jobs similarly (rho about 0.88), as it should,
since both read mostly the same overlap. With a real model the correlation is expected to be lower and the interesting
number; the unranked share is 0% here only because the heuristic never fails (the unit tests cover failing jobs).

## Consequences
- Opening the feed or one job costs nothing for a user whose resume and the jobs have not changed, because the nightly
  run (or the first view) filled the cache; the model is asked only for new or changed pairs, at most 30 per user per
  recall, bounded per user by the daily cap and per night by the run budgets.
- A job-content change re-scores that job once (one more model call), by design: scoring a stale description would show
  reasons that no longer hold. The cut of the description at `description-chars` means edits past it do not invalidate.
- Every `NOT_LLM_SCORED` and `UNRANKED` result is explicit, so the feed (P3.3) can label it instead of presenting a
  coarse score as a model verdict. Nothing here changes a score from feedback (saves, hides) or sends alerts and
  digests: those are P3.3 and P3.4 and will read `rankedMatches` and `match_scores`.
- Stage 1 is deliberately permissive about missing data, so a user with strict preferences still sees jobs that state
  nothing. Stricter modes can be added as a preference later without a migration of scores.
- Matching adds four small public interfaces to `profile`, `embeddings`, `jobs` and `identity`; they are read-only and
  carry no entities, and `ApplicationModules.verify()` passes.
- The fake provider must be chosen explicitly; a deployment that forgets `ANTHROPIC_API_KEY` gets `LLM_UNAVAILABLE`
  fallbacks (stage-2 scores flagged `NOT_LLM_SCORED`), never fake scores presented as a model's.
- Not done here: a real-model eval result (no key was used for this ADR; run `make match-eval ARGS='--provider anthropic'`
  when the prompt or weights change), and a calibration of the weights against user feedback (P3.3 collects the signal).
