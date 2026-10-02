package com.jobfinder.core.applications.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jobfinder.core.applications.internal.ApplicationStore.Row;
import com.jobfinder.core.applications.internal.ExtensionDtos.ApplyContext;
import com.jobfinder.core.applications.internal.ExtensionDtos.ApplyContextJob;
import com.jobfinder.core.applications.internal.ExtensionDtos.ApplyContextPack;
import com.jobfinder.core.documents.ApplicationPacks;
import com.jobfinder.core.jobs.JobApplyLinks;
import com.jobfinder.core.jobs.JobApplyLinks.ApplyLink;
import com.jobfinder.core.shared.ApiException;

/**
 * Read-only: which job is this application form for, and what does the caller already have for it
 * (docs/adr/0035-chrome-extension.md). Jobs, packs and the caller's own applications are read through their public APIs
 * and the tracker's own store, always by the caller's user id.
 */
@Service
class ApplyContextService {

    /** How many stored links the search may look at before they are compared exactly. */
    private static final int CANDIDATES = 50;

    private final JobApplyLinks links;
    private final ApplicationStore store;
    private final ApplicationPacks packs;

    ApplyContextService(JobApplyLinks links, ApplicationStore store, ApplicationPacks packs) {
        this.links = links;
        this.store = store;
        this.packs = packs;
    }

    ApplyContext find(UUID userId, String url) {
        String canonical = ApplyUrls.normalize(url).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "invalid_url", "url must be an http(s) address."));
        List<ApplyLink> matches = links.withApplyUrlContaining(ApplyUrls.searchFragment(canonical), CANDIDATES)
                .stream().filter(c -> ApplyUrls.normalize(c.applyUrl()).filter(canonical::equals).isPresent())
                .toList();
        if (matches.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "job_not_found", "No job is known for this address.");
        }
        // The same posting can be stored twice (two sources): prefer the one the caller already tracks.
        ApplyLink chosen = matches.get(0);
        Optional<Row> application = Optional.empty();
        for (ApplyLink match : matches) {
            Optional<Row> mine = store.findByJob(userId, match.jobId());
            if (mine.isPresent()) {
                chosen = match;
                application = mine;
                break;
            }
        }
        ApplyContextPack pack = packs.latestFor(userId, chosen.jobId())
                .map(p -> new ApplyContextPack(p.id(), p.status(), p.version())).orElse(null);
        return new ApplyContext(new ApplyContextJob(chosen.jobId(), chosen.title(), chosen.company()),
                application.map(Row::id).orElse(null), application.map(Row::status).orElse(null), pack);
    }
}
