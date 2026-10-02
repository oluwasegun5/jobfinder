package com.jobfinder.core.rendering.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The {@code rendered_files} table (V26): the index of what has been rendered and where it is stored. */
@Component
class RenderedFileStore {

    enum SourceType {
        DOCUMENT, RESUME_VERSION
    }

    /** What makes one rendered file different from another. */
    record Variant(UUID userId, SourceType sourceType, UUID sourceId, String contentSha256, RenderTemplate template,
            RenderFormat format, PageFormat pageSize) {
    }

    record Row(UUID id, UUID userId, String storageKey, String fileSha256, long sizeBytes, Instant createdAt,
            RenderTemplate template, RenderFormat format, PageFormat pageSize) {
    }

    private static final String COLUMNS = "id, user_id, storage_key, file_sha256, size_bytes, created_at, template, "
            + "format, page_size";

    private final JdbcClient jdbc;

    RenderedFileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Row> find(Variant v, int rendererVersion) {
        return jdbc.sql("select " + COLUMNS + """
                  from rendered_files
                 where user_id = :userId and source_type = :sourceType and source_id = :sourceId
                   and content_sha256 = :hash and renderer_version = :version and template = :template
                   and format = :format and page_size = :pageSize
                """).param("userId", v.userId()).param("sourceType", v.sourceType().name())
                .param("sourceId", v.sourceId()).param("hash", v.contentSha256()).param("version", rendererVersion)
                .param("template", v.template().name()).param("format", v.format().name())
                .param("pageSize", v.pageSize().name()).query((rs, row) -> map(rs)).optional();
    }

    /** Records the file; false if another request recorded the same variant first (the same key, the same bytes). */
    boolean insert(Variant v, int rendererVersion, String storageKey, String fileSha256, long sizeBytes, Instant now) {
        return jdbc.sql("""
                insert into rendered_files (id, user_id, source_type, source_id, content_sha256, renderer_version,
                                            template, format, page_size, storage_key, file_sha256, size_bytes, created_at)
                values (:id, :userId, :sourceType, :sourceId, :hash, :version, :template, :format, :pageSize, :key,
                        :fileHash, :size, :now)
                on conflict do nothing
                """).param("id", UUID.randomUUID()).param("userId", v.userId())
                .param("sourceType", v.sourceType().name()).param("sourceId", v.sourceId())
                .param("hash", v.contentSha256()).param("version", rendererVersion)
                .param("template", v.template().name()).param("format", v.format().name())
                .param("pageSize", v.pageSize().name()).param("key", storageKey).param("fileHash", fileSha256)
                .param("size", sizeBytes).param("now", java.sql.Timestamp.from(now)).update() == 1;
    }

    /** Every file rendered from one source for this user, oldest first. */
    List<Row> list(UUID userId, SourceType sourceType, UUID sourceId) {
        return jdbc.sql("select " + COLUMNS + """
                  from rendered_files
                 where user_id = :userId and source_type = :sourceType and source_id = :sourceId
                 order by created_at, id
                """).param("userId", userId).param("sourceType", sourceType.name()).param("sourceId", sourceId)
                .query((rs, row) -> map(rs)).list();
    }

    /** One file, only if it belongs to this user and this source. */
    Optional<Row> get(UUID userId, SourceType sourceType, UUID sourceId, UUID id) {
        return jdbc.sql("select " + COLUMNS + """
                  from rendered_files
                 where id = :id and user_id = :userId and source_type = :sourceType and source_id = :sourceId
                """).param("id", id).param("userId", userId).param("sourceType", sourceType.name())
                .param("sourceId", sourceId).query((rs, row) -> map(rs)).optional();
    }

    void delete(UUID id) {
        jdbc.sql("delete from rendered_files where id = :id").param("id", id).update();
    }

    void deleteAll(UUID userId) {
        jdbc.sql("delete from rendered_files where user_id = :userId").param("userId", userId).update();
    }

    private static Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("storage_key"), rs.getString("file_sha256"), rs.getLong("size_bytes"),
                rs.getTimestamp("created_at").toInstant(), RenderTemplate.valueOf(rs.getString("template")),
                RenderFormat.valueOf(rs.getString("format")), PageFormat.valueOf(rs.getString("page_size")));
    }
}
