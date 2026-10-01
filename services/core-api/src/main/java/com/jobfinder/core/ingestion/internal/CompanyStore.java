package com.jobfinder.core.ingestion.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds or creates the employer a job belongs to. Companies are matched on their normalized name
 * (see {@link Names#company}); there is no unique constraint on it (ADR 0018), so find-or-create takes
 * a transaction-scoped advisory lock on the name to keep two sources from creating the same company
 * at the same moment. Call it inside a transaction.
 */
@Component
class CompanyStore {

    record CompanyRef(UUID id, String name, String normalizedName) {
    }

    private final JdbcClient jdbc;

    CompanyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CompanyRef> find(UUID id) {
        return jdbc.sql("select id, name, normalized_name from companies where id = :id")
                .param("id", id)
                .query((rs, row) -> new CompanyRef(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("normalized_name")))
                .optional();
    }

    UUID findOrCreate(String name, String normalizedName, Instant now) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 17))")
                .param("key", "company:" + normalizedName)
                .query()
                .singleRow();
        Optional<UUID> existing = jdbc.sql("""
                select id from companies where normalized_name = :normalized order by created_at, id limit 1
                """)
                .param("normalized", normalizedName)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                insert into companies (id, name, normalized_name, created_at, updated_at)
                values (:id, :name, :normalized, :at, :at)
                """)
                .param("id", id)
                .param("name", name)
                .param("normalized", normalizedName)
                .param("at", at)
                .update();
        return id;
    }
}
