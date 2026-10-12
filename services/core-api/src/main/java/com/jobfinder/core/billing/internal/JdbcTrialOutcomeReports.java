package com.jobfinder.core.billing.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.billing.TrialOutcomeReports;

/** Counts {@code trial_accounts} by state for the accounts created in a range. Counts only. */
@Component
class JdbcTrialOutcomeReports implements TrialOutcomeReports {

    private final JdbcClient jdbc;

    JdbcTrialOutcomeReports(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public TrialOutcomes outcomes(Instant from, Instant to) {
        return jdbc.sql("""
                select count(*) filter (where t.state = 'GRANTED') as granted,
                       count(*) filter (where t.state = 'HOLD') as hold,
                       count(*) filter (where t.state = 'DENIED') as denied
                from trial_accounts t join users u on u.id = t.user_id
                where u.created_at >= :from and u.created_at < :to and u.role <> 'ADMIN' and u.deleted_at is null
                """)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query((ResultSet rs, int row) -> read(rs)).single();
    }

    private static TrialOutcomes read(ResultSet rs) throws SQLException {
        return new TrialOutcomes(rs.getLong("granted"), rs.getLong("hold"), rs.getLong("denied"));
    }
}
