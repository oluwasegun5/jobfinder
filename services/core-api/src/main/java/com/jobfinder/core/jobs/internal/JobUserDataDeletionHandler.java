package com.jobfinder.core.jobs.internal;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;

/** The jobs module's share of an account deletion: the user's saved and hidden jobs. Runs in the deleting transaction. */
@Component
class JobUserDataDeletionHandler {

    private final JdbcClient jdbc;

    JobUserDataDeletionHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        jdbc.sql("delete from user_job_actions where user_id = :userId").param("userId", event.userId()).update();
    }
}
