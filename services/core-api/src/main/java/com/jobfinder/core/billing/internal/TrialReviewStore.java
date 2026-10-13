package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.jobfinder.core.billing.TrialReviews.Action;
import com.jobfinder.core.billing.TrialReviews.Decision;
import com.jobfinder.core.billing.TrialReviews.Reason;
import com.jobfinder.core.billing.TrialReviews.ReviewStatus;
import com.jobfinder.core.billing.TrialReviews.TrialState;
import com.jobfinder.core.billing.TrialReviews.View;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the review queue adds to {@code trial_accounts} (the reasons the gate decided as it did, the milestones reached,
 * the admin's decision) and the append-only {@code trial_review_decisions} audit table
 * (docs/adr/0047-trial-review-queue.md, Flyway V39). The gate's own upsert never touches these columns.
 */
@Component
class TrialReviewStore {

    /** A row of the queue before the milestone bits are turned into codes. */
    record QueueRow(UUID userId, String email, Instant signedUpAt, TrialState state, ReviewStatus reviewStatus,
            int riskScore, List<Reason> reasons, int milestonesReached, int milestonesGranted, Instant trialExpiresAt,
            Instant decidedAt) {
    }

    /** The review-related columns of one account. */
    record ReviewState(int milestonesReached, ReviewStatus reviewStatus) {
    }

    private static final TypeReference<List<Reason>> REASONS = new TypeReference<>() {
    };

    private static final String QUEUE_SELECT = """
            select t.user_id, u.email, u.created_at as signed_up_at, t.state, t.review_decision, t.risk_score,
                   t.decision_reasons, t.milestones_reached, t.milestones, t.expires_at, t.reviewed_at
              from trial_accounts t join users u on u.id = t.user_id
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    TrialReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    // --- written by the gate ---------------------------------------------------------------------------------------

    /** Adds the codes the account does not have yet; a code already recorded keeps its first points. */
    void addReasons(UUID userId, List<Reason> added) {
        if (added.isEmpty()) {
            return;
        }
        List<Reason> merged = new ArrayList<>(reasons(userId));
        for (Reason reason : added) {
            if (merged.stream().noneMatch(r -> r.code().equals(reason.code()))) {
                merged.add(reason);
            }
        }
        jdbc.sql("update trial_accounts set decision_reasons = cast(:reasons as jsonb) where user_id = :user")
                .param("reasons", json.writeValueAsString(merged)).param("user", userId).update();
    }

    /** Remembers that the account reached a milestone whose tranche was not granted. */
    void markReached(UUID userId, int bit) {
        jdbc.sql("update trial_accounts set milestones_reached = milestones_reached | :bit where user_id = :user")
                .param("bit", bit).param("user", userId).update();
    }

    /** Whether an admin approved the account (the gate then skips its rules for the account's later milestones). */
    boolean approved(UUID userId) {
        return jdbc.sql("select exists (select 1 from trial_accounts where user_id = :user "
                + "and review_decision = 'APPROVED')").param("user", userId).query(Boolean.class).single();
    }

    // --- read by the review service --------------------------------------------------------------------------------

    List<Reason> reasons(UUID userId) {
        return jdbc.sql("select decision_reasons from trial_accounts where user_id = :user").param("user", userId)
                .query((rs, n) -> parse(rs.getString(1))).optional().orElse(List.of());
    }

    private List<Reason> parse(String text) {
        return text == null || text.isBlank() ? List.of() : json.readValue(text, REASONS);
    }

    /** The review columns, row-locked: the caller holds the user's ledger lock too. */
    Optional<ReviewState> lockReview(UUID userId) {
        return jdbc.sql("select milestones_reached, review_decision from trial_accounts where user_id = :user for update")
                .param("user", userId)
                .query((rs, n) -> new ReviewState(rs.getInt("milestones_reached"), status(rs.getString("review_decision"))))
                .optional();
    }

    private static ReviewStatus status(String decision) {
        return decision == null ? ReviewStatus.PENDING : ReviewStatus.valueOf(decision);
    }

    private QueueRow map(ResultSet rs, int n) throws SQLException {
        return new QueueRow(rs.getObject("user_id", UUID.class), rs.getString("email"), instant(rs, "signed_up_at"),
                TrialState.valueOf(rs.getString("state")), status(rs.getString("review_decision")),
                rs.getInt("risk_score"), parse(rs.getString("decision_reasons")), rs.getInt("milestones_reached"),
                rs.getInt("milestones"), instant(rs, "expires_at"), instant(rs, "reviewed_at"));
    }

    private static String where(View view, TrialState state) {
        String base = view == View.PENDING
                ? " where t.state in ('HOLD', 'DENIED') and t.review_decision is null"
                : " where t.review_decision is not null";
        return state == null ? base : base + " and t.state = :state";
    }

    /** Oldest first: when the gate first recorded the account, then the user id (a stable tie-break). */
    List<QueueRow> queue(View view, TrialState state, int offset, int limit) {
        var statement = jdbc.sql(QUEUE_SELECT + where(view, state)
                + " order by t.created_at asc, t.user_id asc limit :limit offset :offset");
        if (state != null) {
            statement = statement.param("state", state.name());
        }
        return statement.param("limit", limit).param("offset", offset).query(this::map).list();
    }

    long queueSize(View view, TrialState state) {
        var statement = jdbc.sql("select count(*) from trial_accounts t" + where(view, state));
        if (state != null) {
            statement = statement.param("state", state.name());
        }
        return statement.query(Long.class).single();
    }

    Optional<QueueRow> row(UUID userId) {
        return jdbc.sql(QUEUE_SELECT + " where t.user_id = :user").param("user", userId).query(this::map).optional();
    }

    // --- decisions -------------------------------------------------------------------------------------------------

    /** Records the admin's decision on the account and the milestones now reached. */
    void setReview(UUID userId, ReviewStatus decision, int milestonesReached, Instant now) {
        jdbc.sql("""
                update trial_accounts set review_decision = :decision, reviewed_at = :now,
                       milestones_reached = :reached, updated_at = :now
                 where user_id = :user
                """).param("decision", decision.name()).param("now", at(now)).param("reached", milestonesReached)
                .param("user", userId).update();
    }

    void appendDecision(UUID userId, UUID adminId, Action action, TrialState stateBefore, String note,
            BigDecimal creditsGranted, Instant now) {
        jdbc.sql("""
                insert into trial_review_decisions (user_id, admin_id, action, state_before, note, credits_granted,
                                                    decided_at)
                values (:user, :admin, :action, :before, :note, :credits, :now)
                """).param("user", userId).param("admin", adminId).param("action", action.name())
                .param("before", stateBefore.name()).param("note", note).param("credits", creditsGranted)
                .param("now", at(now)).update();
    }

    /** The decisions taken about one account, newest first. */
    List<Decision> decisions(UUID userId) {
        return jdbc.sql("""
                select admin_id, action, note, credits_granted, decided_at from trial_review_decisions
                 where user_id = :user order by decided_at desc, id desc
                """).param("user", userId)
                .query((rs, n) -> new Decision(rs.getObject("admin_id", UUID.class), Action.valueOf(rs.getString("action")),
                        rs.getString("note"), rs.getBigDecimal("credits_granted"), instant(rs, "decided_at")))
                .list();
    }

    /** Deletes decisions made before {@code cutoff}; the database refuses to delete anything under 730 days old. */
    int purgeDecisionsBefore(Instant cutoff) {
        return jdbc.sql("delete from trial_review_decisions where decided_at < :cutoff").param("cutoff", at(cutoff))
                .update();
    }
}
