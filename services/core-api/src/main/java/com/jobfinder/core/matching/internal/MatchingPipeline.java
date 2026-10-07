package com.jobfinder.core.matching.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jobfinder.core.billing.AiAllowanceException;
import com.jobfinder.core.billing.InsufficientCreditsException;
import com.jobfinder.core.billing.AiUsageGate;
import com.jobfinder.core.embeddings.ResumeEmbeddings;
import com.jobfinder.core.embeddings.ResumeVector;
import com.jobfinder.core.jobs.JobForMatching;
import com.jobfinder.core.jobs.JobMatchSource;
import com.jobfinder.core.jobs.RecalledJob;
import com.jobfinder.core.matching.FallbackReason;
import com.jobfinder.core.matching.MatchResult;
import com.jobfinder.core.matching.MatchesRefreshed;
import com.jobfinder.core.matching.MatchService;
import com.jobfinder.core.matching.MatchStatus;
import com.jobfinder.core.matching.RankedMatches;
import com.jobfinder.core.matching.internal.AiMatchClient.AiUnavailableException;
import com.jobfinder.core.matching.internal.AiMatchClient.JobOutcome;
import com.jobfinder.core.matching.internal.MatchScoreStore.NewScore;
import com.jobfinder.core.matching.internal.MatchScoreStore.Row;
import com.jobfinder.core.matching.internal.Snapshots.CandidateSnapshot;
import com.jobfinder.core.matching.internal.Snapshots.JobSnapshot;
import com.jobfinder.core.matching.internal.Stage2Scorer.Stage2;
import com.jobfinder.core.profile.Candidate;
import com.jobfinder.core.profile.CandidateProfiles;
import com.jobfinder.core.shared.ApiException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The three matching stages (PLAN.md section 7, docs/adr/0026-matching-engine.md):
 * <ol>
 * <li>stage 1, SQL filters from the preferences, and stage 2, cosine recall of the top N against the primary resume
 * embedding blended with skill overlap and recency, both in one query of the jobs module;</li>
 * <li>stage 3, the model re-ranks the best few, through ai-service, with the cache in front.</li>
 * </ol>
 * No database transaction is open while ai-service is called. A model score is cached under (resume version, job,
 * prompt version) with the hashes of the resume snapshot and the job snapshot it was made from, so it is reused until
 * the resume content or the job content changes, and a new primary resume version never sees the old one's scores.
 */
@Service
class MatchingPipeline implements MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchingPipeline.class);

    /** A job under consideration: its content, its stage-2 blend. */
    private record Item(JobForMatching job, Stage2 stage2) {
    }

    /** A job waiting for the model. */
    private record Pending(Item item, JobSnapshot snapshot) {
    }

    private record Reranked(List<MatchResult> results, int cached, int llmScored, int unranked, int notScored,
            int requests, boolean capped) {
    }

    private final CandidateProfiles profiles;
    private final ResumeEmbeddings embeddings;
    private final JobMatchSource jobs;
    private final PreferenceFilters filters;
    private final Stage2Scorer stage2;
    private final Snapshots snapshots;
    private final MatchScoreStore store;
    private final AiMatchClient ai;
    private final AiUsageGate gate;
    private final MatchingProperties properties;
    private final MeterRegistry meters;
    private final ApplicationEventPublisher events;

    MatchingPipeline(CandidateProfiles profiles, ResumeEmbeddings embeddings, JobMatchSource jobs,
            PreferenceFilters filters, Stage2Scorer stage2, Snapshots snapshots, MatchScoreStore store,
            AiMatchClient ai, AiUsageGate gate, MatchingProperties properties, MeterRegistry meters,
            ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.embeddings = embeddings;
        this.jobs = jobs;
        this.filters = filters;
        this.stage2 = stage2;
        this.snapshots = snapshots;
        this.store = store;
        this.ai = ai;
        this.gate = gate;
        this.properties = properties;
        this.meters = meters;
        this.events = events;
    }

    @Override
    public RankedMatches rankedMatches(UUID userId) {
        return rank(userId, false);
    }

    @Override
    public RankedMatches cachedMatches(UUID userId, int limit) {
        Candidate candidate = candidate(userId);
        ResumeVector vector = embeddings.currentVector(candidate.resumeVersionId()).orElseThrow(
                () -> new ApiException(HttpStatus.CONFLICT, "resume_embedding_pending",
                        "Your resume is still being analysed. Try again in a moment."));
        CandidateSnapshot snapshot = snapshots.candidate(candidate);
        List<RecalledJob> recalled = jobs.recall(vector.model(), vector.values(), filters.selectionFor(candidate),
                Math.max(1, Math.min(limit, properties.recallLimit())));
        Instant now = Instant.now();
        Map<UUID, JobForMatching> content = new HashMap<>();
        jobs.jobs(recalled.stream().map(RecalledJob::jobId).toList()).forEach(j -> content.put(j.id(), j));
        String promptVersion = properties.promptVersion();
        Map<UUID, Row> cached = store.find(candidate.resumeVersionId(), promptVersion,
                recalled.stream().map(RecalledJob::jobId).toList());
        List<MatchResult> results = new ArrayList<>();
        int fromCache = 0;
        for (RecalledJob r : recalled) {
            JobForMatching job = content.get(r.jobId());
            if (job == null) {
                continue;
            }
            Item item = new Item(job, stage2.score(r.similarity(), snapshot.skills(), r.skills(), r.postedAt(), now));
            Row row = cached.get(job.id());
            if (row != null && row.resumeHash().equals(snapshot.hash())
                    && row.jobHash().equals(snapshots.job(job).hash())) {
                results.add(llmScored(candidate.resumeVersionId(), promptVersion, item, row.llmScore(),
                        row.strengths(), row.gaps(), row.model(), row.computedAt()));
                fromCache++;
            } else {
                results.add(fallback(candidate.resumeVersionId(), promptVersion, item, MatchStatus.NOT_LLM_SCORED,
                        job.active() ? null : FallbackReason.JOB_EXPIRED));
            }
        }
        return new RankedMatches(userId, candidate.resumeVersionId(), order(results), new RankedMatches.Stats(
                recalled.size(), results.size(), fromCache, 0, 0, results.size() - fromCache, 0, false));
    }

    /** As {@link #rankedMatches}; the nightly batch also requires saved preferences (an onboarded user). */
    RankedMatches rank(UUID userId, boolean requirePreferences) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return doRank(userId, requirePreferences);
        } finally {
            sample.stop(latency("rank"));
        }
    }

    /** PLAN.md section 11, match latency: the time to produce a user's ranked matches or one job's match. */
    private Timer latency(String operation) {
        return Timer.builder("matching.latency").tag("operation", operation).publishPercentileHistogram()
                .register(meters);
    }

    private RankedMatches doRank(UUID userId, boolean requirePreferences) {
        Candidate candidate = candidate(userId);
        if (requirePreferences && !candidate.hasPreferences()) {
            throw new ApiException(HttpStatus.CONFLICT, "preferences_required", "Save your job preferences first.");
        }
        ResumeVector vector = embeddings.currentVector(candidate.resumeVersionId()).orElseThrow(
                () -> new ApiException(HttpStatus.CONFLICT, "resume_embedding_pending",
                        "Your resume is still being analysed. Try again in a moment."));
        CandidateSnapshot snapshot = snapshots.candidate(candidate);
        List<RecalledJob> recalled = jobs.recall(vector.model(), vector.values(), filters.selectionFor(candidate),
                properties.recallLimit());
        Instant now = Instant.now();
        Map<UUID, Stage2> blended = new HashMap<>();
        for (RecalledJob r : recalled) {
            blended.put(r.jobId(), stage2.score(r.similarity(), snapshot.skills(), r.skills(), r.postedAt(), now));
        }
        List<UUID> top = recalled.stream()
                .sorted(Comparator.<RecalledJob>comparingDouble(r -> blended.get(r.jobId()).score()).reversed()
                        .thenComparing(Comparator.comparing(RecalledJob::postedAt).reversed())
                        .thenComparing(RecalledJob::jobId))
                .limit(properties.rerankTop()).map(RecalledJob::jobId).toList();
        Map<UUID, JobForMatching> content = new HashMap<>();
        jobs.jobs(top).forEach(j -> content.put(j.id(), j));
        List<Item> items = top.stream().filter(content::containsKey)
                .map(id -> new Item(content.get(id), blended.get(id))).toList();

        Reranked reranked = rerank(userId, candidate, snapshot, items);
        List<MatchResult> ordered = order(reranked.results());
        if (reranked.llmScored() > 0) {
            publishRefreshed(userId, reranked.llmScored());
        }
        return new RankedMatches(userId, candidate.resumeVersionId(), ordered, new RankedMatches.Stats(
                recalled.size(), items.size(), reranked.cached(), reranked.llmScored(), reranked.unranked(),
                reranked.notScored(), reranked.requests(), reranked.capped()));
    }

    /** Tells the listeners (instant alerts, ADR 0028) that the user's cached matches changed; never fails the run. */
    private void publishRefreshed(UUID userId, int newlyScored) {
        try {
            events.publishEvent(new MatchesRefreshed(userId, Instant.now(), newlyScored));
        } catch (RuntimeException e) {
            log.warn("A listener of MatchesRefreshed failed for user {}", userId, e);
        }
    }

    @Override
    public MatchResult matchJob(UUID userId, UUID jobId) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return doMatchJob(userId, jobId);
        } finally {
            sample.stop(latency("job"));
        }
    }

    private MatchResult doMatchJob(UUID userId, UUID jobId) {
        Candidate candidate = candidate(userId);
        JobForMatching job = jobs.jobs(List.of(jobId)).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "Job not found."));
        CandidateSnapshot snapshot = snapshots.candidate(candidate);
        // Without a current embedding (still being made) the blend simply leaves the similarity out.
        Double cosine = embeddings.currentVector(candidate.resumeVersionId())
                .flatMap(v -> jobs.similarity(jobId, v.model(), v.values())).orElse(null);
        Stage2 blended = stage2.score(cosine, snapshot.skills(), job.skills(), job.postedAt(), Instant.now());
        return rerank(userId, candidate, snapshot, List.of(new Item(job, blended))).results().get(0);
    }

    private Candidate candidate(UUID userId) {
        return profiles.candidate(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "resume_required",
                "Upload your CV first: matching needs a primary resume with parsed content."));
    }

    /**
     * Gives every item a result: its cached model score if still valid, else a fresh one from ai-service in batches.
     * Stops asking at the first sign the user's allowance is spent or ai-service is down, and serves the
     * stage-2 score (flagged) for what is left.
     */
    private Reranked rerank(UUID userId, Candidate candidate, CandidateSnapshot snapshot, List<Item> items) {
        UUID versionId = candidate.resumeVersionId();
        String promptVersion = properties.promptVersion();
        Map<UUID, Row> cached = store.find(versionId, promptVersion, items.stream().map(i -> i.job().id()).toList());
        List<MatchResult> results = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        int fromCache = 0;
        int notScored = 0;
        for (Item item : items) {
            JobSnapshot jobSnapshot = snapshots.job(item.job());
            Row row = cached.get(item.job().id());
            if (row != null && row.resumeHash().equals(snapshot.hash()) && row.jobHash().equals(jobSnapshot.hash())) {
                results.add(llmScored(versionId, promptVersion, item, row.llmScore(), row.strengths(), row.gaps(),
                        row.model(), row.computedAt()));
                fromCache++;
            } else if (!item.job().active()) {
                results.add(fallback(versionId, promptVersion, item, MatchStatus.NOT_LLM_SCORED,
                        FallbackReason.JOB_EXPIRED));
                notScored++;
            } else {
                pending.add(new Pending(item, jobSnapshot));
            }
        }

        int llmScored = 0;
        int unranked = 0;
        int requests = 0;
        boolean capped = false;
        FallbackReason stopped = null;
        for (int from = 0; from < pending.size(); from += properties.llm().batchSize()) {
            List<Pending> batch = pending.subList(from, Math.min(pending.size(), from + properties.llm().batchSize()));
            if (stopped == null) {
                try {
                    gate.requireAllowance(userId, AiMatchClient.FEATURE);
                } catch (AiAllowanceException e) {
                    stopped = e instanceof InsufficientCreditsException ? FallbackReason.INSUFFICIENT_CREDITS
                            : e instanceof com.jobfinder.core.billing.AiConsentRequiredException
                                    ? FallbackReason.CONSENT_REQUIRED : FallbackReason.DAILY_CAP_REACHED;
                    capped = true;
                }
            }
            List<JobOutcome> outcomes = null;
            String model = null;
            if (stopped == null) {
                requests++;
                try {
                    var scored = ai.score(userId, promptVersion, snapshot.json(),
                            batch.stream().map(p -> p.snapshot().json()).toList());
                    outcomes = scored.outcomes();
                    model = scored.model();
                } catch (AiUnavailableException e) {
                    log.warn("Match scoring unavailable, serving stage-2 scores: {}", e.getMessage());
                    stopped = FallbackReason.LLM_UNAVAILABLE;
                }
            }
            for (int i = 0; i < batch.size(); i++) {
                Pending p = batch.get(i);
                JobOutcome outcome = outcomes == null ? null : outcomes.get(i);
                if (outcome != null && outcome.scored()) {
                    String used = model == null ? "unknown" : model;
                    Stage2 s = p.item().stage2();
                    store.upsert(new NewScore(userId, versionId, p.item().job().id(), promptVersion, snapshot.hash(),
                            p.snapshot().hash(), s.cosine(), s.skills(), s.recency(), round3(s.score()),
                            outcome.score(), outcome.strengths(), outcome.gaps(), used));
                    results.add(llmScored(versionId, promptVersion, p.item(), outcome.score(), outcome.strengths(),
                            outcome.gaps(), used, Instant.now()));
                    llmScored++;
                } else if (outcome != null) {
                    results.add(fallback(versionId, promptVersion, p.item(), MatchStatus.UNRANKED,
                            FallbackReason.LLM_FAILED));
                    unranked++;
                } else {
                    results.add(fallback(versionId, promptVersion, p.item(), MatchStatus.NOT_LLM_SCORED, stopped));
                    notScored++;
                }
            }
        }
        meters.counter("matching.ai.requests").increment(requests);
        count("cached", fromCache);
        count("llm_scored", llmScored);
        count("unranked", unranked);
        count("not_scored", notScored);
        return new Reranked(results, fromCache, llmScored, unranked, notScored, requests, capped);
    }

    private void count(String outcome, int amount) {
        if (amount > 0) {
            meters.counter("matching.scores", "outcome", outcome).increment(amount);
        }
    }

    /** Model-scored jobs first by model score, then the rest by stage-2 score; ties by stage-2 score, then id. */
    private static List<MatchResult> order(List<MatchResult> results) {
        Comparator<MatchResult> byModel = Comparator.comparing(
                (MatchResult r) -> r.status() == MatchStatus.LLM_SCORED ? 0 : 1)
                .thenComparing((MatchResult r) -> r.llmScore() == null ? 0 : -r.llmScore())
                .thenComparing(r -> -r.stage2Score())
                .thenComparing(MatchResult::jobId);
        return results.stream().sorted(byModel).toList();
    }

    private static MatchResult llmScored(UUID versionId, String promptVersion, Item item, int score,
            List<String> strengths, List<String> gaps, String model, Instant scoredAt) {
        return new MatchResult(item.job().id(), versionId, MatchStatus.LLM_SCORED, null, score,
                round3(item.stage2().score()), score, strengths, gaps, model, promptVersion, scoredAt);
    }

    private static MatchResult fallback(UUID versionId, String promptVersion, Item item, MatchStatus status,
            FallbackReason reason) {
        double s = round3(item.stage2().score());
        return new MatchResult(item.job().id(), versionId, status, reason, (int) Math.round(s), s, null, List.of(),
                List.of(), null, promptVersion, null);
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
