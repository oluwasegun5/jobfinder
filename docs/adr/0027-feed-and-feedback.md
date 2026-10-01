# 0027. The "For you" feed and the feedback loop

## Status
Accepted

## Context
P3.3 (`PLAN.md` sections 7 and 5). Matching (ADR 0026) can say how well a job fits and keeps a cache of model scores,
and search (ADR 0023) lets a user save and hide jobs into `user_job_actions`. What is missing is the page that lists a
user's best matches and the loop that makes saves, hides and applications change what they see next. `PLAN.md` asks
for "simple weighting; learn-to-rank later" and for the feed to be cheap to open (the nightly run and the on-demand
score do the model work). Left open: where the feed lives, what it reads, how "similar" is decided, how big the
feedback may get, what "applied" means for the feed, how pages stay stable while the user acts, and what the user sees
when there is nothing to show.

## Decision

### A `feed` module on public APIs only
`feed` owns no table and has no public API (controller and services are `internal`). It reads three public APIs:
`matching.MatchService` (new `cachedMatches`), `jobs.JobFeedbackSource` (new, read-only: the user's signals, job
features, display cards) and `profile.CandidateProfiles` (does the user have a resume and preferences). `jobs` keeps
owning `user_job_actions` and its write endpoints; `matching` is untouched in behaviour. There is no cycle
(`feed` depends on `matching`, `jobs`, `profile`, `identity`; none depends on `feed`) and `ApplicationModules.verify()`
passes. **No migration**: V20 already created `user_job_actions` with `SAVED / HIDDEN / APPLIED` in its check
constraint and the primary key `(user_id, job_id, action)`; the highest migration stays V23.

### What the feed reads: the score cache, not a recompute
`GET /feed` calls `MatchService.cachedMatches(userId, poolSize)`: the same stage-1 filters and stage-2 recall as
`rankedMatches` (so hidden jobs and the user's preferences apply), limited to the best `app.feed.pool-size` (100, so
five pages of 20), with each job's **cached** model score if the cache holds a valid one (both content hashes match,
ADR 0026) and otherwise its stage-2 estimate. It never calls ai-service, spends no allowance and writes nothing. A
request therefore costs one recall query, one cache lookup and one load of the pool's job rows, and no model call.

The one exception is a user's first look: if the pool is non-empty and **not one** job in it has a valid model score,
the first page (not later pages) runs the existing public `MatchService.rankedMatches` once, which scores the top 30
under the user's daily cap and falls back to estimates if the cap is reached or ai-service is down, and then reads the
cache again. After that, scores are refreshed by the nightly run and by opening a job (`GET /jobs/{id}/match`); jobs
that appeared since show as estimates until then. Consequence: when the cap is spent or ai-service is down for a user
with nothing cached, each first page retries the cheap cap check (and the 2 s connect timeout when down). That is
bounded and the same fallback ADR 0026 defines; a refresh endpoint is left out until it is needed.

Each item says where its score came from: `scoreSource` is `LLM_SCORED` (the model, with `strengths`, `gaps`, `model`,
`scoredAt`) or `STAGE2_ONLY` (an estimate from embeddings, skills and recency; `fallbackReason` is set when known).
Like search, an item carries the job card (title, company, place, mode, salary, posted, status, summary, saved and
applied flags); the credit each source's terms require is on the job page the card links to (ADR 0021, 0023).

### Ranking: model-scored first, then adjusted score
The order is the one of ADR 0026 with the adjusted score in place of the raw one: model-scored jobs before estimated
ones (the two scales do not mean the same thing), then `feedScore` descending, then stage-2 score descending, then job
id. `feedScore = clamp(matchScore + adjustment, 0, 100)`. `matchScore` is never rewritten: the badge shows what the
model said, and the feed says separately how the user's own actions moved the job.

### Feedback: deterministic, explainable, capped
For a candidate job J and each signal (a saved, hidden or applied job S, not J itself):

| question | test | effect (default points) |
|---|---|---|
| same company? | `company_id` equal | hidden -15, saved +5, applied +4 |
| similar title? | Jaccard of normalized title words >= 0.5 | hidden -15, saved +6, applied +5, times the Jaccard |

Both can hold for one signal and then add. Each effect is multiplied by the signal's age decay
`0.5 ^ (age / half-life)`. Penalties and boosts are summed **separately** and each capped (`max-penalty` 30,
`max-boost` 15); `adjustment = boost - penalty`. The cap is also bounded in configuration: startup fails if either is
above 50, so no configuration can make feedback empty the feed. Jobs are never removed by feedback, only moved; the
single removal is the hide itself.

*Similarity by normalized title words, not embedding cosine.* A title is lower-cased, split on non-alphanumerics, and
stripped of filler words, seniority words, one-letter fragments (`m/f/d`), with developer, programmer, dev, swe and sde
folded into "engineer" and `c++`, `c#`, `.net`, `node.js` kept as one word each. Chosen because it is deterministic,
needs no embedding (a job may not have one yet), costs nothing per request and can be explained to the user in a
sentence ("similar to a job you hid: Java Backend Engineer"). Seniority words are dropped so that hiding a senior
backend role says "not backend work", not "not senior", and so "Frontend Engineer" (1 of 3 words shared) is not taken
for "Backend Engineer". Its weakness is vocabulary: "Platform Reliability Engineer" and "SRE" look unrelated. An
embedding cosine between job vectors would catch those and is the natural second signal later; it would need a job
to job query per signal and a threshold calibrated on real data, which is not available yet. Company equality uses the
normalized company row (ADR 0019), so two spellings of one employer count as one.

*Bounds.* Signals older than `window` (90 days) are ignored, at most `max-signals-per-action` (100) newest of each
kind are read, and decay halves each `half-life` (30 days). A hide from last week counts about 85%, one from three
months ago is gone. All of it is in `app.feed.*` (`application.yml`).

*Explained.* Every item has `adjustment` (signed points) and `reasons`: one entry per cause with a code
(`HIDDEN_SAME_COMPANY`, `HIDDEN_SIMILAR_TITLE`, `SAVED_*`, `APPLIED_*`), signed `points` (scaled so the reasons add up to
the adjustment), `count` of signals and the title of the newest one as `example`, plus `PENALTY_CAPPED` /
`BOOST_CAPPED` entries when a cap applied.

### Actions
`PUT/DELETE /jobs/{id}/save`, `/jobs/{id}/hide` exist (ADR 0023). New: `PUT/DELETE /jobs/{id}/applied` (204,
idempotent, 404 for an unknown job), written to `user_job_actions` as `APPLIED`. Rules, all per user:

- Hiding un-saves, saving un-hides (unchanged). **Marking applied un-hides** (you do not hide what you applied to) and
  keeps a save. Unmarking only removes `APPLIED`. Hiding an applied job keeps `APPLIED`.
- **Hidden** jobs are removed from search, similar jobs and the feed (recall excludes them); undoing a hide restores
  them everywhere and their feedback with them, since feedback is read from the live rows.
- **Applied** jobs **leave the feed** (it is for finding jobs, and the application tracker, P4, owns what happens after)
  but **stay in search, saved jobs and the job page, flagged** `applied: true` (new on `JobSummary` and `JobDetail`; the job page has a "Mark as applied" toggle), and
  count as positive signals for similar jobs. Marking applied does not create a tracker entry: `applications` is P4.

### Paging that survives the user acting
`GET /feed?limit&cursor`: `limit` 1 to 50 (default 20), keyset cursor over the sort key above. The cursor carries
`asOf`, the instant of the first page, and **feedback is read as of that instant** (signals created after it are
ignored by later pages; decay is measured from it). So a hide, save or apply while the user pages on changes the next
*first* page but never the pages they are walking: nothing repeats and nothing is skipped because another job moved.
Removals are the exception that is safe: a job hidden or applied to after the first page is simply absent from the
rest. The pool itself is re-read per page, so a job whose model score arrives mid-walk (nightly run) can move tier; the
worst case is one job seen at a slightly different place, not a loop. Cursors are not signed; a bad one is 400
`invalid_cursor`.

### Empty states
When the first page has no items, `emptyReason` is one of `NO_RESUME` (no primary resume with parsed content: upload
one), `NO_PREFERENCES` (resume but no saved preferences), `RESUME_PROCESSING` (embedding not ready: matching's 409
`resume_embedding_pending`), `NO_MATCHES` (nothing passes the filters, or everything was hidden or applied to). The feed
is made only for users who saved preferences, as the nightly run does (ADR 0026): an unfiltered feed would score
jobs the user has not said they want and spend their allowance on them. In those states no model is called.
These are HTTP 200 with an empty list, not errors: they are states of the page, not failures of the request.

### Web
`/feed` ("For you", second in the main nav after Dashboard, and linked from the dashboard's welcome card; the post-login
landing page stays the dashboard): job cards
with an accessible score badge (the number as text, "AI-scored" or "Estimated", never colour alone), expandable
strengths and gaps (`aria-expanded`), a note when the user's actions moved the job, and save, hide, applied actions
with optimistic updates (hide and applied replace the card with an undo row; failure rolls back and says so), "Load
more", loading, error and the four empty states with links (upload resume, set preferences). Search and saved lists keep
their existing card; the shared facts row is reused.

## Consequences
- Opening the feed costs no model call after the first look, and hiding a job changes the very next load.
- A hidden job's lookalikes drop by up to 15 points and recover as the hide ages; ten hides of one employer cannot take
  more than 30 points off one job, so a strong match (an 85) still lists above a weak one (a 45) while a borderline
  one sinks. That is the intent: feedback re-orders close calls and cannot bury a great match.
- Estimates (`STAGE2_ONLY`) are always listed below model-scored jobs even when an adjustment would put them above:
  feedback re-orders within a score kind.
- The pool is the best 100 by recall, so a user who hides a lot sees jobs from lower in the recall come up; beyond 100
  the feed ends (search is for the rest).
- Title similarity misses synonyms outside its small map; cosine similarity between job embeddings is the planned
  second signal. Weights are guesses to be tuned from real saves and hides (P3.3 collects the signal, learn-to-rank is
  Phase 6).
- Not done here: digests and alerts, saved searches (P3.4), the `VIEWED` signal, a tracker entry when marking applied,
  a feed refresh endpoint.
