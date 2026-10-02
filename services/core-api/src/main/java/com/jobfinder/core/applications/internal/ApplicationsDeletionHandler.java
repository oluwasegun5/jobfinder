package com.jobfinder.core.applications.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * The tracker's share of an account deletion: the user's reminders, status history and applications, and nobody else's.
 * Runs in the deleting transaction.
 */
@Component
class ApplicationsDeletionHandler {

    private final ApplicationStore store;

    ApplicationsDeletionHandler(ApplicationStore store) {
        this.store = store;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        store.purgeAll(event.userId());
    }
}
