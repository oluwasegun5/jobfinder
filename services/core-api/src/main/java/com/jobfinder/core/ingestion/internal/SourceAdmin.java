package com.jobfinder.core.ingestion.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.jobfinder.core.ingestion.IngestionRunPage;
import com.jobfinder.core.ingestion.IngestionRunStatus;
import com.jobfinder.core.ingestion.IngestionRunView;
import com.jobfinder.core.ingestion.SourceAdminService;
import com.jobfinder.core.ingestion.SourceAlertView;
import com.jobfinder.core.ingestion.SourceOverview;
import com.jobfinder.core.ingestion.SourceSchedule;
import com.jobfinder.core.ingestion.UnknownSourceException;

/** The admin dashboard's view of the ingestion module (ADR 0024). Read-mostly: JDBC, like the rest of the module. */
@Service
class SourceAdmin implements SourceAdminService {

    private static final String RUN_COLUMNS = """
            r.id, s.code, r.status, r.started_at, r.finished_at, r.targets, r.fetched, r.created, r.updated,
            r.expired, r.errors, r.error_summary
            """;

    private final JdbcClient jdbc;
    private final SourceStore sources;
    private final SourceAlertStore alerts;
    private final IngestionRunner runner;
    private final IngestionProperties properties;

    private final RowMapper<IngestionRunView> runMapper = (rs, row) -> new IngestionRunView(
            rs.getObject("id", UUID.class), rs.getString("code"), IngestionRunStatus.valueOf(rs.getString("status")),
            rs.getObject("started_at", OffsetDateTime.class).toInstant(), instant(rs, "finished_at"),
            rs.getInt("targets"), rs.getInt("fetched"), rs.getInt("created"), rs.getInt("updated"),
            rs.getInt("expired"), rs.getInt("errors"), rs.getString("error_summary"));

    SourceAdmin(JdbcClient jdbc, SourceStore sources, SourceAlertStore alerts, IngestionRunner runner,
            IngestionProperties properties) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.alerts = alerts;
        this.runner = runner;
        this.properties = properties;
    }

    @Override
    public List<SourceOverview> sources() {
        Map<UUID, int[]> targetCounts = targetCounts();
        Map<UUID, IngestionRunView> lastRuns = lastFinishedRuns();
        Map<UUID, Boolean> running = runningSources();
        Map<UUID, List<SourceAlertView>> openAlerts = openAlerts();
        return sources.findAll().stream()
                .filter(source -> runner.adapters().containsKey(source.code()))
                .map(source -> overview(source, targetCounts.getOrDefault(source.id(), new int[2]),
                        lastRuns.get(source.id()), running.getOrDefault(source.id(), false),
                        openAlerts.getOrDefault(source.id(), List.of())))
                .toList();
    }

    @Override
    public SourceOverview setEnabled(String sourceCode, boolean enabled) {
        if (!runner.adapters().containsKey(sourceCode) || !sources.setEnabled(sourceCode, enabled)) {
            throw new UnknownSourceException(sourceCode);
        }
        return sources().stream().filter(source -> source.code().equals(sourceCode)).findFirst()
                .orElseThrow(() -> new UnknownSourceException(sourceCode));
    }

    @Override
    public RunStart startRun(String sourceCode) {
        return runner.startAsync(sourceCode);
    }

    @Override
    public IngestionRunPage runs(String sourceCode, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be at least 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        if (sourceCode != null && !runner.adapters().containsKey(sourceCode)) {
            throw new UnknownSourceException(sourceCode);
        }
        String filter = sourceCode == null ? "" : " where s.code = :code";
        JdbcClient.StatementSpec count = jdbc.sql(
                "select count(*) from ingestion_runs r join sources s on s.id = r.source_id" + filter);
        JdbcClient.StatementSpec select = jdbc.sql("select " + RUN_COLUMNS
                + " from ingestion_runs r join sources s on s.id = r.source_id" + filter
                + " order by r.started_at desc, r.id limit :limit offset :offset");
        if (sourceCode != null) {
            count = count.param("code", sourceCode);
            select = select.param("code", sourceCode);
        }
        long total = count.query(Long.class).single();
        List<IngestionRunView> items = select.param("limit", size).param("offset", (long) page * size)
                .query(runMapper).list();
        return new IngestionRunPage(items, page, size, total, (int) Math.ceil(total / (double) size));
    }

    private SourceOverview overview(SourceStore.SourceRow source, int[] targets, IngestionRunView lastRun,
            boolean running, List<SourceAlertView> openAlerts) {
        String unavailable = runner.adapters().get(source.code()).unavailableReason().orElse(null);
        SourceSchedule schedule = !source.enabled() ? SourceSchedule.DISABLED
                : unavailable != null ? SourceSchedule.UNAVAILABLE
                        : !properties.scheduler().enabled() ? SourceSchedule.SCHEDULER_OFF : SourceSchedule.SCHEDULED;
        Instant nextDue = schedule == SourceSchedule.SCHEDULED
                ? source.settings().nextDue(source.code(), source.lastRunAt()) : null;
        return new SourceOverview(source.code(), source.kind(), source.enabled(), schedule, unavailable,
                source.health().name(), source.lastRunAt(), nextDue, running, targets[0], targets[1], lastRun,
                openAlerts);
    }

    /** {enabled, total} targets per source. */
    private Map<UUID, int[]> targetCounts() {
        Map<UUID, int[]> counts = new HashMap<>();
        jdbc.sql("""
                select source_id, count(*) filter (where enabled) as enabled_count, count(*) as total
                from source_targets group by source_id
                """).query((rs, row) -> {
                    counts.put(rs.getObject("source_id", UUID.class),
                            new int[] { rs.getInt("enabled_count"), rs.getInt("total") });
                    return null;
                }).list();
        return counts;
    }

    private Map<UUID, IngestionRunView> lastFinishedRuns() {
        Map<UUID, IngestionRunView> latest = new HashMap<>();
        jdbc.sql("select distinct on (r.source_id) r.source_id as source_id, " + RUN_COLUMNS
                + " from ingestion_runs r join sources s on s.id = r.source_id where r.status <> 'RUNNING'"
                + " order by r.source_id, r.started_at desc")
                .query((rs, row) -> {
                    latest.put(rs.getObject("source_id", UUID.class), runMapper.mapRow(rs, row));
                    return null;
                }).list();
        return latest;
    }

    /** A RUNNING row counts only while it can still be real: the lock of a crashed instance expires. */
    private Map<UUID, Boolean> runningSources() {
        Map<UUID, Boolean> running = new HashMap<>();
        Duration limit = properties.lockAtMostFor();
        jdbc.sql("""
                select distinct source_id from ingestion_runs
                 where status = 'RUNNING' and started_at > now() - make_interval(secs => :seconds)
                """)
                .param("seconds", (double) limit.toSeconds())
                .query((rs, row) -> {
                    running.put(rs.getObject("source_id", UUID.class), true);
                    return null;
                }).list();
        return running;
    }

    private Map<UUID, List<SourceAlertView>> openAlerts() {
        Map<UUID, List<SourceAlertView>> byClass = new HashMap<>();
        for (SourceAlertStore.AlertRow row : alerts.findActive()) {
            byClass.computeIfAbsent(row.sourceId(), id -> new java.util.ArrayList<>()).add(new SourceAlertView(
                    row.rule().name(), row.firstFiredAt(), row.lastNotifiedAt(), row.occurrences(), row.detail()));
        }
        return byClass;
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
