package com.jobfinder.core.documents.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;

/** Documents' share of the data export: tailored resumes, cover letters, screening answers and application packs. */
@Component
class DocumentsDataExporter implements UserDataExporter {

    private final JdbcClient jdbc;

    DocumentsDataExporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "documents";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("documents", UserDataJson.rows(jdbc,
                "select t.* from generated_documents t where t.user_id = :userId order by t.created_at, t.id", userId));
        bundle.json("application-packs", UserDataJson.rows(jdbc,
                "select t.* from application_packs t where t.user_id = :userId order by t.created_at, t.id", userId));
    }
}
