package com.jobfinder.core.interview.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * Interview prep's share of an account deletion: every prep of the user, with its questions and brief, and nobody
 * else's. Runs in the deleting transaction.
 */
@Component
class InterviewDeletionHandler {

    private final InterviewStore store;

    InterviewDeletionHandler(InterviewStore store) {
        this.store = store;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        store.purgeAll(event.userId());
    }
}
