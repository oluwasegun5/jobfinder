package com.jobfinder.core.interview.internal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/**
 * Interview prep's share of an account deletion: every prep of the user, with its questions and brief, and every mock
 * interview session with its turns and feedback, and nobody else's. Runs in the deleting transaction.
 */
@Component
class InterviewDeletionHandler {

    private final InterviewStore store;
    private final MockInterviewStore sessions;

    InterviewDeletionHandler(InterviewStore store, MockInterviewStore sessions) {
        this.store = store;
        this.sessions = sessions;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        sessions.purgeAll(event.userId());
        store.purgeAll(event.userId());
    }
}
