package com.jobfinder.core.jobs.internal;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.jobs.JobCard;
import com.jobfinder.core.jobs.JobFeedbackSource;
import com.jobfinder.core.jobs.JobSearchCriteria;
import com.jobfinder.core.jobs.NewJobs;
import com.jobfinder.core.jobs.NewJobsSource;

/**
 * "What is new for this saved search" (docs/adr/0028-notifications.md): the search's own filters, applied by the same
 * conditions as {@code GET /jobs}, restricted to jobs ingestion stored inside a time window.
 */
@Component
class NewJobsReader implements NewJobsSource {

    private final JobSearchRepository repository;
    private final JobFeedbackSource cards;

    NewJobsReader(JobSearchRepository repository, JobFeedbackSource cards) {
        this.repository = repository;
        this.cards = cards;
    }

    @Override
    @Transactional(readOnly = true)
    public NewJobs newSince(UUID userId, JobSearchCriteria c, Instant after, Instant until, int limit) {
        String q = c.q() == null || c.q().isBlank() ? null : c.q().replaceAll("\\s+", " ").strip();
        JobFilters filters = new JobFilters(sorted(c.workModes(), false), sorted(c.employmentTypes(), false),
                sorted(c.seniorities(), false), sorted(c.countries(), true),
                c.location() == null || c.location().isBlank() ? null : c.location().strip().toLowerCase(Locale.ROOT),
                null, c.minSalary(), c.currency() == null ? null : c.currency().toUpperCase(Locale.ROOT), null);
        int total = repository.countNewSince(userId, q, filters, after, until);
        if (total == 0 || limit < 1) {
            return total == 0 ? NewJobs.NONE : new NewJobs(total, List.of());
        }
        List<UUID> ids = repository.newSince(userId, q, filters, after, until, limit);
        Map<UUID, JobCard> byId = this.cards.cards(userId, ids);
        List<JobCard> jobs = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return new NewJobs(total, jobs);
    }

    private static List<String> sorted(List<String> values, boolean upper) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank())
                .map(v -> upper ? v.strip().toUpperCase(Locale.ROOT) : v.strip()).distinct().sorted().toList();
    }
}
