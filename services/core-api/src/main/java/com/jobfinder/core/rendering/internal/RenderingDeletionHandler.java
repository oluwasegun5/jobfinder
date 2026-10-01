package com.jobfinder.core.rendering.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;
import com.jobfinder.core.storage.ObjectStorage;

/**
 * Rendering's share of an account deletion, shaped like profile's (docs/adr/0015-cv-upload-and-storage.md): the index
 * rows first, then every rendered file by prefix, inside the deleting transaction, so a storage failure rolls the
 * whole deletion back rather than leaving someone's resume in the bucket. The prefix also catches a file that lost
 * its row.
 */
@Component
class RenderingDeletionHandler {

    private final RenderedFileStore store;
    private final ObjectStorage storage;

    RenderingDeletionHandler(RenderedFileStore store, ObjectStorage storage) {
        this.store = store;
        this.storage = storage;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        store.deleteAll(event.userId());
        storage.deleteByPrefix(RenderService.storagePrefix(event.userId()));
    }
}
