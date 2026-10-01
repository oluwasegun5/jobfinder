package com.jobfinder.core.documents.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * Documents' share of an account deletion: every document of the user goes, approved ones included (the only code
 * allowed to remove approved documents, see V25's trigger). Runs in the deleting transaction.
 */
@Component
class DocumentsDeletionHandler {

    private final DocumentStore store;

    DocumentsDeletionHandler(DocumentStore store) {
        this.store = store;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        store.purgeAll(event.userId());
    }
}
