package com.jobfinder.core.jobs.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.ingestion.JobListing;
import com.jobfinder.core.ingestion.JobListingService;
import com.jobfinder.core.ingestion.SourceAttribution;
import com.jobfinder.core.jobs.internal.JobDtos.Attribution;
import com.jobfinder.core.jobs.internal.JobDtos.JobDetail;
import com.jobfinder.core.jobs.internal.JobDtos.JobPage;
import com.jobfinder.core.jobs.internal.JobDtos.JobSummary;
import com.jobfinder.core.jobs.internal.JobDtos.Listing;
import com.jobfinder.core.jobs.internal.JobDtos.SearchParams;
import com.jobfinder.core.jobs.internal.JobSearchRepository.After;
import com.jobfinder.core.jobs.internal.JobSearchRepository.Hit;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Job search, "similar jobs", the job page and the caller's saved list (docs/adr/0023-job-search.md). Every method
 * takes the caller's user ID from the controller (from the access token): hidden jobs are removed from the caller's
 * results and the saved flag is the caller's own.
 */
@Service
class JobSearchService {

    private static final String KEYWORD = "K";
    private static final String RECENT = "R";
    private static final String SIMILAR = "S";
    private static final String SAVED = "V";

    private final JobSearchRepository repository;
    private final JobListingService listings;
    private final JsonMapper json;
    private final Clock clock;

    JobSearchService(JobSearchRepository repository, JobListingService listings, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.listings = listings;
        this.json = json;
        this.clock = clock;
    }

    /** Keyword search ranked by text relevance and recency, or newest first when there is no keyword. */
    @Transactional(readOnly = true)
    JobPage search(UUID userId, SearchParams params) {
        String q = keyword(params.q());
        JobFilters filters = filters(params);
        int limit = limit(params);
        String mode = q == null ? RECENT : KEYWORD;
        String scope = digest(mode, (q == null ? "" : q) + "#" + filters.key());
        PageCursor cursor = cursor(params, mode, scope);
        Instant asOf = cursor == null ? now() : cursor.asOfInstant();
        After after = cursor == null ? null : new After(cursor.key(), cursor.id());
        List<Hit> hits = q == null ? repository.recent(userId, filters, asOf, after, limit + 1)
                : repository.keyword(userId, q, filters, asOf, after, limit + 1);
        return page(userId, hits, limit, mode, scope, asOf);
    }

    /** The jobs nearest to this one by embedding, with the same filters as a search. At most N neighbours exist. */
    @Transactional(readOnly = true)
    JobPage similar(UUID userId, UUID jobId, SearchParams params) {
        if (keyword(params.q()) != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "keyword_not_supported",
                    "Similar jobs are found by embedding; search by keyword with GET /jobs instead.");
        }
        JobFilters filters = filters(params);
        int limit = limit(params);
        JobSearchRepository.SimilarSource source = repository.similarSource(jobId);
        if (source == null) {
            throw notFound();
        }
        if (!source.embedded()) {
            throw new ApiException(HttpStatus.CONFLICT, "embedding_unavailable",
                    "This job has not been embedded yet, so similar jobs are not available for it.");
        }
        String scope = digest(SIMILAR, jobId + "#" + filters.key());
        PageCursor cursor = cursor(params, SIMILAR, scope);
        Instant asOf = cursor == null ? now() : cursor.asOfInstant();
        After after = cursor == null ? null : new After(cursor.key(), cursor.id());
        List<Hit> hits = repository.similar(userId, jobId, source.embeddingModel(), filters, asOf, after, limit + 1);
        return page(userId, hits, limit, SIMILAR, scope, asOf);
    }

    /** The caller's saved jobs, most recently saved first. */
    @Transactional(readOnly = true)
    JobPage saved(UUID userId, Integer requestedLimit, String token) {
        int limit = requestedLimit == null ? JobDtos.DEFAULT_LIMIT : requestedLimit;
        String scope = digest(SAVED, "");
        PageCursor cursor = token == null || token.isBlank() ? null : PageCursor.decode(json, token, SAVED, scope);
        After after = cursor == null ? null : new After(cursor.key(), cursor.id());
        List<Hit> hits = repository.saved(userId, after, limit + 1);
        JobPage page = page(userId, hits, limit, SAVED, scope, now());
        List<JobSummary> items = new ArrayList<>();
        for (int i = 0; i < page.items().size(); i++) {
            items.add(page.items().get(i).withSavedAt(java.time.OffsetDateTime.parse(hits.get(i).key()).toInstant()));
        }
        return new JobPage(items, page.nextCursor());
    }

    @Transactional(readOnly = true)
    JobDetail detail(UUID userId, UUID jobId) {
        JobDetail job = repository.detail(userId, jobId).orElseThrow(JobSearchService::notFound);
        List<Listing> jobListings = listings.listingsOf(List.of(jobId)).getOrDefault(jobId, List.of()).stream()
                .map(JobSearchService::listing).toList();
        return new JobDetail(job.id(), job.title(), job.company(), job.location(), job.city(), job.country(),
                job.workMode(), job.employmentType(), job.seniority(), job.salary(), job.postedAt(), job.expiresAt(),
                job.status(), job.description(), job.skills(), job.applyUrl(), jobListings, job.saved(), job.hidden(),
                job.applied(), job.similarAvailable());
    }

    @Transactional
    void save(UUID userId, UUID jobId) {
        requireJob(jobId);
        repository.save(userId, jobId, now());
    }

    @Transactional
    void unsave(UUID userId, UUID jobId) {
        requireJob(jobId);
        repository.unsave(userId, jobId);
    }

    /** Marks the job as applied to (and un-hides it: you do not hide what you applied to). */
    @Transactional
    void markApplied(UUID userId, UUID jobId) {
        requireJob(jobId);
        repository.markApplied(userId, jobId, now());
    }

    @Transactional
    void unmarkApplied(UUID userId, UUID jobId) {
        requireJob(jobId);
        repository.unmarkApplied(userId, jobId);
    }

    @Transactional
    void hide(UUID userId, UUID jobId) {
        requireJob(jobId);
        repository.hide(userId, jobId, now());
    }

    @Transactional
    void unhide(UUID userId, UUID jobId) {
        requireJob(jobId);
        repository.unhide(userId, jobId);
    }

    // --- helpers ---

    private JobPage page(UUID userId, List<Hit> hits, int limit, String mode, String scope, Instant asOf) {
        boolean more = hits.size() > limit;
        List<Hit> pageHits = more ? hits.subList(0, limit) : hits;
        Map<UUID, JobSummary> byId = repository.summaries(userId, pageHits.stream().map(Hit::id).toList());
        List<JobSummary> items = new ArrayList<>();
        for (Hit hit : pageHits) {
            JobSummary summary = byId.get(hit.id());
            if (summary == null) {
                continue; // deleted between the two queries
            }
            items.add(SIMILAR.equals(mode) ? summary.withSimilarity(hit.similarity()) : summary);
        }
        String next = null;
        if (more) {
            Hit last = pageHits.get(pageHits.size() - 1);
            next = new PageCursor(PageCursor.VERSION, mode, last.key(), last.id(), asOf.toEpochMilli(), scope)
                    .encode(json);
        }
        return new JobPage(items, next);
    }

    private PageCursor cursor(SearchParams params, String mode, String scope) {
        String token = params.cursor();
        return token == null || token.isBlank() ? null : PageCursor.decode(json, token, mode, scope);
    }

    private static String digest(String mode, String query) {
        return PageCursor.digest(mode + "|" + query);
    }

    private static String keyword(String q) {
        if (q == null) {
            return null;
        }
        String cleaned = q.replaceAll("\\s+", " ").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static JobFilters filters(SearchParams p) {
        if (p.minSalary() != null && (p.salaryCurrency() == null || p.salaryCurrency().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "salary_currency_required",
                    "A minimum salary needs salaryCurrency: amounts in different currencies are never compared.");
        }
        return JobFilters.of(p.workMode(), p.employmentType(), p.seniority(), p.country(), p.location(),
                p.companyId(), p.minSalary(), p.salaryCurrency(), p.postedWithinDays());
    }

    private static int limit(SearchParams p) {
        return p.limit() == null ? JobDtos.DEFAULT_LIMIT : p.limit();
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private void requireJob(UUID jobId) {
        if (!repository.exists(jobId)) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "No such job.");
    }

    private static Listing listing(JobListing listing) {
        SourceAttribution a = listing.attribution();
        return new Listing(listing.sourceCode(), listing.sourceKind().name(), listing.listingUrl(),
                a == null ? null : new Attribution(a.name(), a.text(), a.url(), a.notes()));
    }
}
