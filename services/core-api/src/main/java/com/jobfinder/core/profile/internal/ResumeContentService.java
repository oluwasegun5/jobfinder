package com.jobfinder.core.profile.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobfinder.core.profile.internal.ResumeContentDtos.Education;
import com.jobfinder.core.profile.internal.ResumeContentDtos.Experience;
import com.jobfinder.core.profile.internal.ResumeContentDtos.ParseWarning;
import com.jobfinder.core.profile.internal.ResumeContentDtos.ResumeContent;
import com.jobfinder.core.profile.internal.ResumeContentDtos.ResumeContentResponse;
import com.jobfinder.core.profile.ResumeVersionChanged;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and edits the structured content of a resume: the parser's output (version 1) and the user's corrections.
 * Every method scopes by the caller's user ID, so someone else's resume is reported as not found.
 *
 * <p>Editing never touches the parser's upload version. The first save adds an EDIT version; later saves update
 * that EDIT version in place while it is the latest, so a user who keeps tweaking does not pile up rows. Saving is
 * allowed in every parse state (PENDING, FAILED, PARSED): a failed or slow parse must not block the user from
 * filling their profile by hand. A parse that finishes after an edit only fills the upload version and never
 * replaces what the user saved.
 */
@Service
class ResumeContentService {

    private static final TypeReference<List<ParseWarning>> WARNINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    ResumeContentService(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.events = events;
    }

    ResumeContentResponse get(UUID userId, UUID resumeId) {
        return tx.execute(status -> {
            lockOwned(userId, resumeId, false);
            return read(resumeId);
        });
    }

    ResumeContentResponse save(UUID userId, UUID resumeId, ResumeContent content) {
        checkConsistency(content);
        String structured = write(content);
        return tx.execute(status -> {
            // The row lock serialises concurrent saves, so version numbers cannot collide.
            lockOwned(userId, resumeId, true);
            record Latest(UUID id, int number, String source) {
            }
            Latest latest = jdbc.sql("""
                    select id, version_number, source from resume_versions
                    where resume_id = :id order by version_number desc limit 1
                    """).param("id", resumeId)
                    .query((rs, row) -> new Latest(rs.getObject("id", UUID.class), rs.getInt("version_number"),
                            rs.getString("source")))
                    .single();
            if (ResumeVersion.Source.EDIT.name().equals(latest.source())) {
                jdbc.sql("""
                        update resume_versions set structured = cast(:structured as jsonb), updated_at = now()
                        where id = :versionId
                        """).param("structured", structured).param("versionId", latest.id()).update();
                events.publishEvent(new ResumeVersionChanged(latest.id()));
            } else {
                UUID versionId = UUID.randomUUID();
                jdbc.sql("""
                        insert into resume_versions (id, resume_id, version_number, structured, source, created_at, updated_at)
                        values (:versionId, :id, :number, cast(:structured as jsonb), 'EDIT', now(), now())
                        """).param("versionId", versionId).param("id", resumeId)
                        .param("number", latest.number() + 1).param("structured", structured).update();
                events.publishEvent(new ResumeVersionChanged(versionId));
            }
            jdbc.sql("update resumes set updated_at = now() where id = :id").param("id", resumeId).update();
            return read(resumeId);
        });
    }

    private void lockOwned(UUID userId, UUID resumeId, boolean forUpdate) {
        boolean found = jdbc.sql("select id from resumes where id = :id and user_id = :userId"
                + (forUpdate ? " for update" : "")).param("id", resumeId).param("userId", userId)
                .query(UUID.class).optional().isPresent();
        if (!found) {
            throw new ApiException(HttpStatus.NOT_FOUND, "resume_not_found", "Resume not found.");
        }
    }

    /** The latest version of the resume. Every resume has one from the moment it is uploaded. */
    private ResumeContentResponse read(UUID resumeId) {
        return jdbc.sql("""
                select r.parse_status, r.parse_error, v.version_number, v.source, v.updated_at,
                       v.structured::text as structured, v.parse_warnings::text as warnings
                from resumes r join resume_versions v on v.resume_id = r.id
                where r.id = :id order by v.version_number desc limit 1
                """).param("id", resumeId)
                .query((rs, row) -> new ResumeContentResponse(resumeId, ParseStatus.valueOf(rs.getString("parse_status")),
                        rs.getString("parse_error"), rs.getInt("version_number"),
                        ResumeVersion.Source.valueOf(rs.getString("source")), parseContent(rs.getString("structured")),
                        parseWarnings(rs.getString("warnings")), rs.getTimestamp("updated_at").toInstant()))
                .single();
    }

    private ResumeContent parseContent(String structured) {
        if (structured == null) {
            return null;
        }
        try {
            return json.readValue(structured, ResumeContent.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored resume content is unreadable", e);
        }
    }

    private List<ParseWarning> parseWarnings(String warnings) {
        if (warnings == null) {
            return List.of();
        }
        try {
            return json.readValue(warnings, WARNINGS);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private String write(ResumeContent content) {
        try {
            return json.writeValueAsString(content);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise resume content", e);
        }
    }

    /** Rules that span fields, which annotations cannot express. */
    private static void checkConsistency(ResumeContent content) {
        for (int i = 0; i < content.experience().size(); i++) {
            Experience job = content.experience().get(i);
            if (job.isCurrent() && job.endDate() != null) {
                throw invalid("experience[" + i + "]", "A current role cannot have an end date.");
            }
            if (endsBeforeStart(job.startDate(), job.endDate())) {
                throw invalid("experience[" + i + "]", "The end date is before the start date.");
            }
        }
        for (int i = 0; i < content.education().size(); i++) {
            Education school = content.education().get(i);
            if (endsBeforeStart(school.startDate(), school.endDate())) {
                throw invalid("education[" + i + "]", "The end date is before the start date.");
            }
        }
    }

    /** Partial ISO dates of mixed precision compare correctly on their shared prefix. */
    private static boolean endsBeforeStart(String start, String end) {
        if (start == null || end == null) {
            return false;
        }
        int shared = Math.min(start.length(), end.length());
        return end.substring(0, shared).compareTo(start.substring(0, shared)) < 0;
    }

    private static ApiException invalid(String path, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_resume_content", path + ": " + detail);
    }
}
