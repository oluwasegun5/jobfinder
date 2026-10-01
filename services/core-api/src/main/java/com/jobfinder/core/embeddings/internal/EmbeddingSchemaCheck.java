package com.jobfinder.core.embeddings.internal;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to start if the configured dimension is not the one the {@code vector(n)} columns were created with, so a
 * changed {@code EMBEDDING_DIMENSION} without its migration fails loudly at boot instead of at the first write.
 */
@Component
class EmbeddingSchemaCheck implements ApplicationRunner {

    private final JdbcClient jdbc;
    private final EmbeddingProperties properties;

    EmbeddingSchemaCheck(JdbcClient jdbc, EmbeddingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String expected = "vector(" + properties.dimension() + ")";
        List<String> columns = jdbc.sql("""
                select c.relname || '.' || a.attname || ' is ' || format_type(a.atttypid, a.atttypmod)
                  from pg_attribute a join pg_class c on c.oid = a.attrelid
                 where c.relnamespace = current_schema()::regnamespace
                   and c.relname in ('jobs', 'resume_versions') and a.attname = 'embedding'
                   and not a.attisdropped and format_type(a.atttypid, a.atttypmod) <> :expected
                """).param("expected", expected).query(String.class).list();
        if (!columns.isEmpty()) {
            throw new IllegalStateException("app.embeddings.dimension is " + properties.dimension() + " but "
                    + String.join(", ", columns) + "; changing the dimension needs a migration (docs/adr/0022)");
        }
    }
}
