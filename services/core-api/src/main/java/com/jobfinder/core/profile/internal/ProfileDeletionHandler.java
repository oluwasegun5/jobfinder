package com.jobfinder.core.profile.internal;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDeletionRequested;
import com.jobfinder.core.storage.ObjectStorage;

/**
 * Profile's share of an account deletion: rows first (resume versions go with their resume),
 * then every stored file. Runs in the deleting transaction, so a storage failure rolls the
 * whole deletion back rather than leaving CVs behind. Files are removed by prefix, which also
 * catches any object that lost its row.
 */
@Component
class ProfileDeletionHandler {

    private final JdbcClient jdbc;
    private final ObjectStorage storage;

    ProfileDeletionHandler(JdbcClient jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @EventListener
    void on(UserDeletionRequested event) {
        for (String table : new String[] { "resumes", "preferences", "profiles" }) {
            jdbc.sql("delete from " + table + " where user_id = :userId").param("userId", event.userId()).update();
        }
        storage.deleteByPrefix(ResumeService.storagePrefix(event.userId()));
    }
}
