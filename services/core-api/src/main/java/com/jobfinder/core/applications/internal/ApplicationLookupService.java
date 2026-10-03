package com.jobfinder.core.applications.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jobfinder.core.applications.ApplicationLookup;

/** {@link ApplicationLookup} over the tracker's own store; always scoped by the owner. */
@Service
class ApplicationLookupService implements ApplicationLookup {

    private final ApplicationStore store;

    ApplicationLookupService(ApplicationStore store) {
        this.store = store;
    }

    @Override
    public Optional<ApplicationRef> owned(UUID userId, UUID applicationId) {
        return store.find(userId, applicationId).map(row -> new ApplicationRef(row.id(), row.jobId()));
    }
}
