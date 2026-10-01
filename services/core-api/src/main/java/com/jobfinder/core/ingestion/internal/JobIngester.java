package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.FetchTarget;
import com.jobfinder.core.ingestion.JobSourceAdapter;
import com.jobfinder.core.ingestion.NormalizerInput;
import com.jobfinder.core.ingestion.RawPosting;

/**
 * Turns one stored raw posting into a job: map it (the adapter), normalize it, find or create its
 * company, then either create the job, refresh it, or merge it into the job that already has its
 * fingerprint, recording the listing in {@code job_sources} either way. Call it inside the target's
 * transaction.
 *
 * <p>
 * Merge policy (ADR 0019): the first listing of a job is its owner and its refreshes overwrite the
 * job's content; any other listing only fills what the job lacks and keeps it active. So an
 * aggregator that re-lists an ATS job can add a missing salary but never rewrite the employer's own
 * title or description.
 */
@Component
class JobIngester {

    enum Result {
        /** A new job was created. */
        CREATED,
        /** An existing job was refreshed, or this listing was merged into it. */
        UPDATED,
        /** The posting could not be mapped or normalized; it stays raw and produces no job. */
        REJECTED,
        /** The adapter has no mapping yet; the posting stays raw. */
        UNMAPPED
    }

    private static final Logger log = LoggerFactory.getLogger(JobIngester.class);

    private final JobNormalizer normalizer;
    private final JobStore jobs;
    private final CompanyStore companies;

    JobIngester(JobNormalizer normalizer, JobStore jobs, CompanyStore companies) {
        this.normalizer = normalizer;
        this.jobs = jobs;
        this.companies = companies;
    }

    Result ingest(SourceStore.SourceRow source, SourceStore.TargetRow target, JobSourceAdapter adapter,
            RawPosting posting, CompanyStore.CompanyRef targetCompany, Instant now) {
        NormalizedJob job;
        try {
            Optional<NormalizerInput> input = adapter.toNormalizerInput(posting,
                    new FetchTarget(target.id(), target.identifier(), target.companyId()));
            if (input.isEmpty()) {
                return Result.UNMAPPED;
            }
            job = normalizer.normalize(input.get(), targetCompany == null ? null : targetCompany.name());
        } catch (RuntimeException e) {
            log.warn("Source {}: posting {} rejected: {}", source.code(), posting.externalId(), e.getMessage());
            return Result.REJECTED;
        }

        UUID companyId = targetCompany != null ? targetCompany.id()
                : companies.findOrCreate(job.companyName(), job.normalizedCompany(), now);
        Optional<JobStore.Link> link = jobs.findLink(source.id(), posting.externalId());
        Optional<UUID> sameFingerprint = jobs.findByFingerprint(job.fingerprint());

        UUID jobId;
        Result result = Result.UPDATED;
        if (sameFingerprint.isPresent()) {
            jobId = sameFingerprint.get();
            if (link.isPresent() && link.get().jobId().equals(jobId) && link.get().owner()) {
                jobs.overwrite(jobId, companyId, job, now);
            } else {
                jobs.fill(jobId, job, now);
            }
        } else if (link.isPresent() && jobs.countLinks(link.get().jobId()) == 1) {
            // The listing changed identity (a retitled or relocated job) and nothing else lists the old
            // one: the job simply changed.
            jobId = link.get().jobId();
            jobs.overwrite(jobId, companyId, job, now);
        } else {
            jobId = UUID.randomUUID();
            if (jobs.insert(jobId, companyId, job, now)) {
                result = Result.CREATED;
            } else {
                // Another source created it between our lookup and our insert: merge into it.
                jobId = jobs.findByFingerprint(job.fingerprint()).orElseThrow();
                jobs.fill(jobId, job, now);
            }
        }

        jobs.upsertLink(jobId, source.id(), target.id(), posting.externalId(), job.applyUrl(), now);
        if (link.isPresent() && !link.get().jobId().equals(jobId) && jobs.countLinks(link.get().jobId()) == 0) {
            // The listing moved to another job and left its old job with no listing at all.
            jobs.expireJob(link.get().jobId(), now);
        }
        return result;
    }
}
