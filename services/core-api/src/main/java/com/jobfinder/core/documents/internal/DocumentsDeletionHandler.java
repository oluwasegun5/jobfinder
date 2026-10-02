package com.jobfinder.core.documents.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * Documents' share of an account deletion: every application pack and every document of the user goes, approved ones included (the only code
 * allowed to remove approved documents, see V25's trigger). Runs in the deleting transaction.
 */
@Component
class DocumentsDeletionHandler {

    private final DocumentStore store;
    private final PackStore packs;

    DocumentsDeletionHandler(DocumentStore store, PackStore packs) {
        this.store = store;
        this.packs = packs;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        packs.purgeAll(event.userId());
        store.purgeAll(event.userId());
    }
}
