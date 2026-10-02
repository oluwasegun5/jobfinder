package com.jobfinder.core.documents.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jobfinder.core.documents.ApplicationPacks;

/** Implements the module's public pack API: a pack is found through its owner, never by id alone. */
@Component
class PackReader implements ApplicationPacks {

    private final PackStore packs;

    PackReader(PackStore packs) {
        this.packs = packs;
    }

    @Override
    public Optional<UUID> jobOf(UUID userId, UUID packId) {
        return packs.find(userId, packId).map(PackStore.PackRow::jobId);
    }

    @Override
    public Optional<PackSummary> latestFor(UUID userId, UUID jobId) {
        return packs.list(userId, jobId, 1).stream()
                .map(p -> new PackSummary(p.id(), p.status().name(), p.version())).findFirst();
    }
}
