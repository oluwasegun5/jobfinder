package com.jobfinder.core.profile.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.identity.UserDataBundle;
import com.jobfinder.core.identity.UserDataExporter;
import com.jobfinder.core.shared.UserDataJson;
import com.jobfinder.core.storage.ObjectNotFoundException;
import com.jobfinder.core.storage.ObjectStorage;

/** Profile's share of the data export: profile, preferences, every CV with its parsed versions, and the CV files. */
@Component
class ProfileDataExporter implements UserDataExporter {

    private record CvFile(UUID id, String key) {
    }

    private final JdbcClient jdbc;
    private final ObjectStorage storage;

    ProfileDataExporter(JdbcClient jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Override
    public String module() {
        return "profile";
    }

    @Override
    public void export(UUID userId, UserDataBundle bundle) {
        bundle.json("profile", UserDataJson.rows(jdbc, "select t.* from profiles t where t.user_id = :userId", userId));
        bundle.json("preferences",
                UserDataJson.rows(jdbc, "select t.* from preferences t where t.user_id = :userId", userId));
        bundle.json("resumes", UserDataJson.rows(jdbc,
                "select t.* from resumes t where t.user_id = :userId order by t.created_at, t.id", userId, "file_key"));
        bundle.json("resume-versions", UserDataJson.rows(jdbc, """
                select v.* from resume_versions v join resumes r on r.id = v.resume_id
                 where r.user_id = :userId order by r.created_at, r.id, v.version_number
                """, userId, "embedding", "embedding_model", "embedding_input_hash", "embedded_at"));
        List<CvFile> files = jdbc.sql("select id, file_key from resumes where user_id = :userId order by created_at, id")
                .param("userId", userId).query((rs, n) -> new CvFile(rs.getObject("id", UUID.class),
                        rs.getString("file_key")))
                .list();
        for (CvFile cv : files) {
            String path = "cv-files/" + cv.id() + extension(cv.key());
            try {
                bundle.file(path, storage.get(cv.key()));
            } catch (ObjectNotFoundException e) {
                bundle.skipped(path, "the stored file no longer exists");
            }
        }
    }

    private static String extension(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? "" : key.substring(dot);
    }
}
