package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.RawPosting;

/** Stores postings as received, one row per (source, external id). */
@Component
class RawPostingStore {

    private final JdbcClient jdbc;

    RawPostingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the posting, or overwrites the stored copy and bumps its fetch time. Returns true if
     * the posting is new. ({@code xmax = 0} is how Postgres tells an inserted row from one updated by
     * {@code ON CONFLICT} in the same statement.)
     */
    boolean upsert(UUID sourceId, UUID targetId, RawPosting posting, Instant fetchedAt) {
        return Boolean.TRUE.equals(jdbc.sql("""
                insert into raw_job_postings (id, source_id, target_id, external_id, payload, fetched_at, created_at,
                                              updated_at)
                values (:id, :sourceId, :targetId, :externalId, cast(:payload as jsonb), :fetchedAt, now(), now())
                on conflict (source_id, external_id) do update
                   set payload = excluded.payload, target_id = excluded.target_id,
                       fetched_at = excluded.fetched_at, updated_at = now()
                returning (xmax = 0)
                """)
                .param("id", UUID.randomUUID())
                .param("sourceId", sourceId)
                .param("targetId", targetId)
                .param("externalId", posting.externalId())
                .param("payload", posting.payload())
                .param("fetchedAt", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                .query(Boolean.class)
                .single());
    }
}
