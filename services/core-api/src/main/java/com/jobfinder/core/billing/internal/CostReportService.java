package com.jobfinder.core.billing.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobfinder.core.billing.AiCostReports;
import com.jobfinder.core.billing.CostReport;
import com.jobfinder.core.billing.CostRow;

/** Cost, tokens and calls from {@code ai_calls}, grouped by day, feature and model (days are UTC days). */
@Service
class CostReportService implements AiCostReports {

    private static final BigDecimal MICRO = BigDecimal.valueOf(1_000_000);
    private static final String DAY = "(created_at at time zone 'UTC')::date";

    private final JdbcClient jdbc;

    CostReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public CostReport report(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException(
                    "The range must be at most " + MAX_RANGE_DAYS + " days and end on or after its start");
        }
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        CostRow totals = rows(start, end, "null::date as day, null::text as feature, null::text as model", null, null)
                .stream().findFirst().orElseThrow();
        return new CostReport(from, to, totals,
                rows(start, end, "null::date as day, feature, null::text as model", "feature", "cost desc, feature"),
                rows(start, end, DAY + " as day, null::text as feature, null::text as model", DAY, "day"),
                rows(start, end, "null::date as day, null::text as feature, model", "model", "cost desc, model"),
                rows(start, end, DAY + " as day, feature, null::text as model", DAY + ", feature",
                        "day, cost desc, feature"));
    }

    /** The grouping columns and clauses are fixed strings from this class, never request input. */
    private List<CostRow> rows(OffsetDateTime start, OffsetDateTime end, String keys, String groupBy, String orderBy) {
        String sql = "select " + keys + ", count(*) as calls, count(*) filter (where status = 'FAILED') as failed, "
                + "coalesce(sum(input_tokens), 0) as input_tokens, coalesce(sum(output_tokens), 0) as output_tokens, "
                + "coalesce(sum(cost_micro_usd), 0) as cost from ai_calls "
                + "where created_at >= :start and created_at < :end"
                + (groupBy == null ? "" : " group by " + groupBy) + (orderBy == null ? "" : " order by " + orderBy);
        return jdbc.sql(sql).param("start", start).param("end", end)
                .query((rs, row) -> new CostRow(rs.getObject("day", LocalDate.class), rs.getString("feature"),
                        rs.getString("model"), rs.getLong("calls"), rs.getLong("failed"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"), BigDecimal.valueOf(rs.getLong("cost")).divide(MICRO, 6,
                                java.math.RoundingMode.UNNECESSARY)))
                .list();
    }
}
