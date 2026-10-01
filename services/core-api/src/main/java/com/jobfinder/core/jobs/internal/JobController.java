package com.jobfinder.core.jobs.internal;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.jobs.internal.JobDtos.JobDetail;
import com.jobfinder.core.jobs.internal.JobDtos.JobPage;
import com.jobfinder.core.jobs.internal.JobDtos.SearchParams;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Job search, the job page and the caller's saved and hidden jobs. Every endpoint needs a signed-in user (see the
 * security configuration); the user comes from the access token, never from the request.
 */
@RestController
class JobController {

    private final JobSearchService jobs;

    JobController(JobSearchService jobs) {
        this.jobs = jobs;
    }

    /**
     * Search active jobs. With {@code q}: keyword search (best match first, newer jobs slightly ahead of older ones
     * of equal relevance). Without: newest first. Hidden jobs are left out. Pages are keyset-paged: pass
     * {@code nextCursor} back as {@code cursor}, with the same other parameters.
     */
    @GetMapping("/jobs")
    JobPage search(@Valid @ParameterObject SearchParams params) {
        return jobs.search(CurrentUser.require().id(), params);
    }

    /** The full job, with every source's listing and the attribution its terms require. */
    @GetMapping("/jobs/{id}")
    JobDetail job(@PathVariable UUID id) {
        return jobs.detail(CurrentUser.require().id(), id);
    }

    /** Active jobs nearest to this one by embedding (at most the nearest 100), with the search filters. */
    @GetMapping("/jobs/{id}/similar")
    JobPage similar(@PathVariable UUID id, @Valid @ParameterObject SearchParams params) {
        return jobs.similar(CurrentUser.require().id(), id, params);
    }

    @PutMapping("/jobs/{id}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void save(@PathVariable UUID id) {
        jobs.save(CurrentUser.require().id(), id);
    }

    @DeleteMapping("/jobs/{id}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsave(@PathVariable UUID id) {
        jobs.unsave(CurrentUser.require().id(), id);
    }

    /** Hides the job from the caller's searches (and removes it from their saved jobs). */
    @PutMapping("/jobs/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hide(@PathVariable UUID id) {
        jobs.hide(CurrentUser.require().id(), id);
    }

    @DeleteMapping("/jobs/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unhide(@PathVariable UUID id) {
        jobs.unhide(CurrentUser.require().id(), id);
    }

    /** The caller's saved jobs, most recently saved first (expired jobs stay listed, marked by their status). */
    @GetMapping("/saved-jobs")
    JobPage saved(@RequestParam(required = false) @Min(1) @Max(JobDtos.MAX_LIMIT) Integer limit,
            @RequestParam(required = false) @Size(max = 600) String cursor) {
        return jobs.saved(CurrentUser.require().id(), limit, cursor);
    }
}
