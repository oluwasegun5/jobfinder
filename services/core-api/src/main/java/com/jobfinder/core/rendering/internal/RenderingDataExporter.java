package com.jobfinder.core.rendering.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;
import com.jobfinder.core.storage.ObjectNotFoundException;
import com.jobfinder.core.storage.ObjectStorage;

/** Rendering's share of the data export: the generated PDF and DOCX files and the index describing them. */
@Component
class RenderingDataExporter implements UserDataExporter {

    private record Rendered(UUID id, String key, String format) {
    }

    private final JdbcClient jdbc;
    private final ObjectStorage storage;

    RenderingDataExporter(JdbcClient jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Override
    public String module() {
        return "generated-files";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("files", UserDataJson.rows(jdbc,
                "select t.* from rendered_files t where t.user_id = :userId order by t.created_at, t.id", userId,
                "storage_key"));
        List<Rendered> files = jdbc
                .sql("select id, storage_key, format from rendered_files where user_id = :userId order by created_at, id")
                .param("userId", userId).query((rs, n) -> new Rendered(rs.getObject("id", UUID.class),
                        rs.getString("storage_key"), rs.getString("format")))
                .list();
        for (Rendered file : files) {
            String path = "files/" + file.id() + "." + file.format().toLowerCase(java.util.Locale.ROOT);
            try {
                bundle.file(path, storage.get(file.key()));
            } catch (ObjectNotFoundException e) {
                bundle.skipped(path, "the stored file no longer exists");
            }
        }
    }
}
