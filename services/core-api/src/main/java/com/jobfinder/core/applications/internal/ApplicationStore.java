package com.jobfinder.core.applications.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.applications.internal.ApplicationDtos.ApplicationStatus;

/**
 * The SQL of {@code applications} and {@code application_events}. Every read and write takes the owner's user id: an
 * application is only ever found through its owner, so someone else's id is "not found", never an error of another kind.
 */
@Repository
class ApplicationStore {

    /** One application row. {@code nextReminderAt} is the earliest PENDING reminder, computed on read. */
    record Row(UUID id, UUID userId, UUID jobId, String title, String company, String url, ApplicationStatus status,
            String notes, Instant appliedAt, UUID packId, UUID resumeDocumentId, UUID coverLetterDocumentId,
            UUID screeningAnswersDocumentId, Instant statusChangedAt, Instant nextReminderAt, Instant createdAt,
            Instant updatedAt) {
    }

    /** One status change. */
    record Event(UUID id, ApplicationStatus from, ApplicationStatus to, String note, Instant at) {
    }

    private static final String COLUMNS = """
            a.id, a.user_id, a.job_id, a.title, a.company, a.url, a.status, a.notes, a.applied_at, a.pack_id,
            a.resume_document_id, a.cover_letter_document_id, a.screening_answers_document_id, a.status_changed_at,
            (select min(r.due_at) from reminders r where r.application_id = a.id and r.state = 'PENDING')
                as next_reminder_at,
            a.created_at, a.updated_at
            """;

    private final JdbcClient jdbc;

    ApplicationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the application. Throws DuplicateKeyException if the user already has one for this job. */
    void insert(Row row) {
        jdbc.sql("""
                insert into applications (id, user_id, job_id, title, company, url, status, notes, applied_at, pack_id,
                        resume_document_id, cover_letter_document_id, screening_answers_document_id,
                        status_changed_at, created_at, updated_at)
                values (:id, :userId, :jobId, :title, :company, :url, :status, :notes, :appliedAt, :packId,
                        :resumeId, :letterId, :answersId, :changedAt, :createdAt, :updatedAt)
                """).param("id", row.id()).param("userId", row.userId()).param("jobId", row.jobId())
                .param("title", row.title()).param("company", row.company()).param("url", row.url())
                .param("status", row.status().name()).param("notes", row.notes())
                .param("appliedAt", timestamp(row.appliedAt())).param("packId", row.packId())
                .param("resumeId", row.resumeDocumentId()).param("letterId", row.coverLetterDocumentId())
                .param("answersId", row.screeningAnswersDocumentId()).param("changedAt", timestamp(row.statusChangedAt()))
                .param("createdAt", timestamp(row.createdAt())).param("updatedAt", timestamp(row.updatedAt())).update();
    }

    void insertEvent(UUID id, UUID applicationId, UUID userId, ApplicationStatus from, ApplicationStatus to,
            String note, Instant at) {
        jdbc.sql("""
                insert into application_events (id, application_id, user_id, from_status, to_status, note, at)
                values (:id, :applicationId, :userId, :from, :to, :note, :at)
                """).param("id", id).param("applicationId", applicationId).param("userId", userId)
                .param("from", from == null ? null : from.name()).param("to", to.name()).param("note", note)
                .param("at", timestamp(at)).update();
    }

    Optional<Row> find(UUID userId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from applications a where a.id = :id and a.user_id = :userId")
                .param("id", id).param("userId", userId).query((rs, n) -> row(rs)).optional();
    }

    /** The application, locked until the transaction ends: for a change that reads the row first. */
    Optional<Row> findForUpdate(UUID userId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from applications a where a.id = :id and a.user_id = :userId for update of a")
                .param("id", id).param("userId", userId).query((rs, n) -> row(rs)).optional();
    }

    Optional<Row> findByJob(UUID userId, UUID jobId) {
        return jdbc.sql("select " + COLUMNS + " from applications a where a.user_id = :userId and a.job_id = :jobId")
                .param("userId", userId).param("jobId", jobId).query((rs, n) -> row(rs)).optional();
    }

    /** The user's applications, optionally of some statuses, by status change (newest first). */
    List<Row> list(UUID userId, Collection<ApplicationStatus> statuses, int limit) {
        String[] names = statuses.stream().map(Enum::name).toArray(String[]::new);
        return jdbc.sql("select " + COLUMNS + """
                 from applications a
                 where a.user_id = :userId and (cardinality(cast(:statuses as text[])) = 0 or a.status = any(cast(:statuses as text[])))
                 order by a.status_changed_at desc, a.id
                 limit :limit
                """).param("userId", userId).param("statuses", names).param("limit", limit)
                .query((rs, n) -> row(rs)).list();
    }

    /** How many applications the user has in each status (every status present). */
    Map<ApplicationStatus, Integer> counts(UUID userId) {
        Map<ApplicationStatus, Integer> counts = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus s : ApplicationStatus.values()) {
            counts.put(s, 0);
        }
        jdbc.sql("select status, count(*) as n from applications where user_id = :userId group by status")
                .param("userId", userId).query((rs, n) -> {
                    counts.put(ApplicationStatus.valueOf(rs.getString("status")), rs.getInt("n"));
                    return null;
                }).list();
        return counts;
    }

    int countAll(UUID userId) {
        return jdbc.sql("select count(*) from applications where user_id = :userId").param("userId", userId)
                .query(Integer.class).single();
    }

    void update(UUID id, String title, String company, String url, String notes, Instant appliedAt, Instant now) {
        jdbc.sql("""
                update applications
                   set title = :title, company = :company, url = :url, notes = :notes, applied_at = :appliedAt,
                       updated_at = :now
                 where id = :id
                """).param("id", id).param("title", title).param("company", company).param("url", url)
                .param("notes", notes).param("appliedAt", timestamp(appliedAt)).param("now", timestamp(now)).update();
    }

    void changeStatus(UUID id, ApplicationStatus status, Instant appliedAt, Instant now) {
        jdbc.sql("""
                update applications
                   set status = :status, applied_at = :appliedAt, status_changed_at = :now, updated_at = :now
                 where id = :id
                """).param("id", id).param("status", status.name()).param("appliedAt", timestamp(appliedAt))
                .param("now", timestamp(now)).update();
    }

    boolean delete(UUID userId, UUID id) {
        return jdbc.sql("delete from applications where id = :id and user_id = :userId").param("id", id)
                .param("userId", userId).update() == 1;
    }

    /** The status history of one application, oldest first. */
    List<Event> events(UUID userId, UUID applicationId) {
        return jdbc.sql("""
                select id, from_status, to_status, note, at from application_events
                 where application_id = :applicationId and user_id = :userId
                 order by seq
                """).param("applicationId", applicationId).param("userId", userId).query((rs, n) -> new Event(
                        rs.getObject("id", UUID.class),
                        rs.getString("from_status") == null ? null : ApplicationStatus.valueOf(rs.getString("from_status")),
                        ApplicationStatus.valueOf(rs.getString("to_status")), rs.getString("note"),
                        rs.getTimestamp("at").toInstant())).list();
    }

    /** Account deletion: reminders, then events, then applications (the foreign keys cascade too; this is explicit). */
    void purgeAll(UUID userId) {
        for (String table : new String[] { "reminders", "application_events", "applications" }) {
            jdbc.sql("delete from " + table + " where user_id = :userId").param("userId", userId).update();
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getString("title"), rs.getString("company"),
                rs.getString("url"), ApplicationStatus.valueOf(rs.getString("status")), rs.getString("notes"),
                instant(rs, "applied_at"), rs.getObject("pack_id", UUID.class),
                rs.getObject("resume_document_id", UUID.class), rs.getObject("cover_letter_document_id", UUID.class),
                rs.getObject("screening_answers_document_id", UUID.class), instant(rs, "status_changed_at"),
                instant(rs, "next_reminder_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
