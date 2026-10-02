package com.jobfinder.core.applications.internal;

import static com.jobfinder.core.applications.internal.ApplicationStore.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobfinder.core.applications.internal.ApplicationDtos.CancelReason;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderKind;
import com.jobfinder.core.applications.internal.ApplicationDtos.ReminderState;

/**
 * The SQL of {@code reminders}. Reads and changes made for a user take the owner's user id and the application id; the
 * sender's own queries ({@link #claimDue}, {@link #markSent}, {@link #markCancelled}) work on reminders of every user and
 * are used by nothing else.
 */
@Repository
class ReminderStore {

    record Row(UUID id, UUID applicationId, UUID userId, ReminderKind kind, String note, Instant dueAt,
            ReminderState state, Instant sentAt, CancelReason cancelReason, int attempts, Instant createdAt) {
    }

    private static final String COLUMNS = """
            id, application_id, user_id, kind, note, due_at, state, sent_at, cancel_reason, attempts, created_at
            """;

    private final JdbcClient jdbc;

    ReminderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID applicationId, UUID userId, ReminderKind kind, String note, Instant dueAt, Instant now) {
        jdbc.sql("""
                insert into reminders (id, application_id, user_id, kind, note, due_at, state, attempts, created_at,
                        updated_at)
                values (:id, :applicationId, :userId, :kind, :note, :dueAt, 'PENDING', 0, :now, :now)
                """).param("id", id).param("applicationId", applicationId).param("userId", userId)
                .param("kind", kind.name()).param("note", note).param("dueAt", timestamp(dueAt))
                .param("now", timestamp(now)).update();
    }

    Optional<Row> find(UUID userId, UUID applicationId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from reminders where id = :id and application_id = :applicationId "
                + "and user_id = :userId").param("id", id).param("applicationId", applicationId)
                .param("userId", userId).query((rs, n) -> row(rs)).optional();
    }

    /** All reminders of the application, soonest first. */
    List<Row> list(UUID userId, UUID applicationId) {
        return jdbc.sql("select " + COLUMNS + " from reminders where application_id = :applicationId "
                + "and user_id = :userId order by due_at, created_at, id").param("applicationId", applicationId)
                .param("userId", userId).query((rs, n) -> row(rs)).list();
    }

    int countPending(UUID applicationId) {
        return jdbc.sql("select count(*) from reminders where application_id = :applicationId and state = 'PENDING'")
                .param("applicationId", applicationId).query(Integer.class).single();
    }

    /** Cancels a PENDING reminder; false when it is not pending (already sent or cancelled). */
    boolean cancel(UUID id, CancelReason reason, Instant now) {
        return jdbc.sql("""
                update reminders set state = 'CANCELLED', cancel_reason = :reason, updated_at = :now
                 where id = :id and state = 'PENDING'
                """).param("id", id).param("reason", reason.name()).param("now", timestamp(now)).update() == 1;
    }

    /** Cancels every pending reminder of an application (it was closed). */
    int cancelPending(UUID applicationId, CancelReason reason, Instant now) {
        return jdbc.sql("""
                update reminders set state = 'CANCELLED', cancel_reason = :reason, updated_at = :now
                 where application_id = :applicationId and state = 'PENDING'
                """).param("applicationId", applicationId).param("reason", reason.name())
                .param("now", timestamp(now)).update();
    }

    /**
     * Gives up on the pending, due reminders that are out of attempts and whose last attempt is older than
     * {@code retryAfter}: they are CANCELLED with SEND_FAILED.
     */
    int giveUp(Instant now, int maxAttempts, Duration retryAfter) {
        return jdbc.sql("""
                update reminders set state = 'CANCELLED', cancel_reason = 'SEND_FAILED', updated_at = :now
                 where state = 'PENDING' and due_at <= :now and attempts >= :maxAttempts and claimed_at < :staleBefore
                """).param("now", timestamp(now)).param("maxAttempts", maxAttempts)
                .param("staleBefore", timestamp(now.minus(retryAfter))).update();
    }

    /**
     * Claims up to {@code limit} due reminders for this sender, in one statement: the rows are locked with
     * {@code FOR UPDATE SKIP LOCKED}, so a second instance running at the same time claims other rows, and the claim
     * (an attempt counted and {@code claimed_at} set) is what keeps a third run from sending them again while the mail is
     * going out. A reminder is claimable when it is PENDING, due, has attempts left and was never claimed or was claimed
     * longer than {@code retryAfter} ago (a sender that died, or a send that failed and is due for another try).
     */
    List<Row> claimDue(Instant now, int maxAttempts, Duration retryAfter, int limit) {
        return jdbc.sql("""
                update reminders r set claimed_at = :now, attempts = r.attempts + 1, updated_at = :now
                 where r.id in (select id from reminders
                                 where state = 'PENDING' and due_at <= :now and attempts < :maxAttempts
                                   and (claimed_at is null or claimed_at < :staleBefore)
                                 order by due_at, id
                                 limit :limit
                                 for update skip locked)
                returning r.id, r.application_id, r.user_id, r.kind, r.note, r.due_at, r.state, r.sent_at,
                          r.cancel_reason, r.attempts, r.created_at
                """).param("now", timestamp(now)).param("maxAttempts", maxAttempts)
                .param("staleBefore", timestamp(now.minus(retryAfter))).param("limit", limit)
                .query((rs, n) -> row(rs)).list();
    }

    /** Marks a claimed reminder SENT; false when it was cancelled meanwhile. */
    boolean markSent(UUID id, Instant now) {
        return jdbc.sql("""
                update reminders set state = 'SENT', sent_at = :now, updated_at = :now
                 where id = :id and state = 'PENDING'
                """).param("id", id).param("now", timestamp(now)).update() == 1;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("application_id", UUID.class),
                rs.getObject("user_id", UUID.class), ReminderKind.valueOf(rs.getString("kind")), rs.getString("note"),
                rs.getTimestamp("due_at").toInstant(), ReminderState.valueOf(rs.getString("state")),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                rs.getString("cancel_reason") == null ? null : CancelReason.valueOf(rs.getString("cancel_reason")),
                rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant());
    }
}
