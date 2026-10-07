package com.jobfinder.core.rendering.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.compliance.RetentionProperties;
import com.jobfinder.core.compliance.RetentionTask;
import com.jobfinder.core.storage.ObjectStorage;

/**
 * A rendered PDF or DOCX is a cache: the source (resume version or document) stays and the file can be made again. Past
 * the retention period the object is deleted from storage, then its index row. Storage first: if it fails the row stays
 * and the next run tries again, so no object is ever left without a row that can find it.
 */
@Component
class RenderedFileRetention implements RetentionTask {

    private static final int BATCH = 500;

    private record Row(UUID id, String key) {
    }

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final RetentionProperties properties;

    RenderedFileRetention(JdbcClient jdbc, ObjectStorage storage, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "rendered-files";
    }

    @Override
    public int purge(Instant now) {
        var cutoff = now.minus(properties.renderedFiles()).atOffset(ZoneOffset.UTC);
        int removed = 0;
        while (true) {
            List<Row> batch = jdbc.sql("select id, storage_key from rendered_files where created_at < :cutoff "
                    + "order by created_at limit :batch").param("cutoff", cutoff).param("batch", BATCH)
                    .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("storage_key"))).list();
            for (Row row : batch) {
                storage.delete(row.key());
                removed += jdbc.sql("delete from rendered_files where id = :id").param("id", row.id()).update();
            }
            if (batch.size() < BATCH) {
                return removed;
            }
        }
    }
}
