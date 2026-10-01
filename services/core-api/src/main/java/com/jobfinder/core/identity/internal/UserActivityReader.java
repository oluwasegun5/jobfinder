package com.jobfinder.core.identity.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserActivity;

/** Reads activity from the refresh tokens: one is issued at every login and every session renewal. */
@Component
class UserActivityReader implements UserActivity {

    private final JdbcClient jdbc;

    UserActivityReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> activeSince(Instant since, UUID afterId, int limit) {
        return jdbc.sql("""
                select u.id from users u
                 where u.status = 'ACTIVE' and u.deleted_at is null and u.id > :after
                   and exists (select 1 from refresh_tokens t where t.user_id = u.id and t.created_at >= :since)
                 order by u.id
                 limit :limit
                """)
                .param("after", afterId).param("since", since.atOffset(ZoneOffset.UTC)).param("limit", limit)
                .query(UUID.class).list();
    }
}
