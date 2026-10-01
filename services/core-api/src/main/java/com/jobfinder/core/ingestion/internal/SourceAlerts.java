package com.jobfinder.core.ingestion.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.jobfinder.core.ingestion.IngestionRunStatus;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Decides, after each run, whether a source needs an admin's attention, and says so by email (ADR 0024).
 * Called by {@link IngestionPipeline} while it still holds the source's lock, so the evaluations of one source never
 * overlap. It never throws: a failure here is logged and must not lose or fail the run.
 *
 * <p>
 * Two rules, each with its own state in {@code source_alerts}:
 * <ul>
 * <li>{@link AlertRule#ZERO_JOBS}: a run that ended SUCCEEDED (every target fetched) with at least one target
 * fetched nothing, and the previous SUCCEEDED run fetched at least {@code zeroJobsMinPrevious} postings. While
 * the alert is open the rule stays in breach for as long as clean runs keep fetching nothing, whatever the
 * baseline, and it clears on the first run that fetches something. Skipped for incremental adapters (an empty
 * answer there is normal), for runs that had errors (the other rule covers them) and for runs with no targets;
 * a skipped run changes nothing.
 * <li>{@link AlertRule#ERROR_RATE}: more than {@code errorRateThreshold} of the run's targets failed. A run of
 * fewer than {@code errorRateMinTargets} targets only counts when every target failed, because one dead board
 * among three says little about the source. A run that does not breach clears the alert.
 * </ul>
 * An alert is emailed when it opens and again only if it is still open {@code reAlertInterval} after the last
 * email. With no recipients it is logged instead, with the same throttling. If sending fails the alert is
 * retried on the next run.
 */
@Component
class SourceAlerts {

    private static final Logger log = LoggerFactory.getLogger(SourceAlerts.class);
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@,;<>]+@[^\\s@,;<>]+");

    record RunOutcome(SourceStore.SourceRow source, boolean fullListing, UUID runId, Instant startedAt,
            IngestionRunStatus status, int targets, IngestionRunStore.Counts counts, Instant finishedAt) {
    }

    private final SourceAlertStore store;
    private final IngestionRunStore runs;
    private final JavaMailSender mailSender;
    private final MeterRegistry meters;
    private final IngestionProperties.Alerts config;
    private final List<String> recipients;

    SourceAlerts(SourceAlertStore store, IngestionRunStore runs, JavaMailSender mailSender, MeterRegistry meters,
            IngestionProperties properties) {
        this.store = store;
        this.runs = runs;
        this.mailSender = mailSender;
        this.meters = meters;
        this.config = properties.alerts();
        this.recipients = config.recipients().stream().map(String::strip).filter(s -> !s.isEmpty())
                .filter(address -> {
                    boolean valid = ADDRESS.matcher(address).matches();
                    if (!valid) {
                        log.warn("Ignoring an unusable address in app.ingestion.alerts.recipients");
                    }
                    return valid;
                }).distinct().toList();
    }

    void evaluate(RunOutcome run) {
        try {
            zeroJobs(run);
        } catch (RuntimeException e) {
            log.error("Source {}: evaluating the zero-jobs alert failed", run.source().code(), e);
        }
        try {
            errorRate(run);
        } catch (RuntimeException e) {
            log.error("Source {}: evaluating the error-rate alert failed", run.source().code(), e);
        }
    }

    private void zeroJobs(RunOutcome run) {
        if (!run.fullListing() || run.status() != IngestionRunStatus.SUCCEEDED || run.targets() == 0) {
            return;
        }
        UUID sourceId = run.source().id();
        if (run.counts().fetched() > 0) {
            resolve(run, AlertRule.ZERO_JOBS);
            return;
        }
        boolean open = store.find(sourceId, AlertRule.ZERO_JOBS).filter(SourceAlertStore.AlertRow::active).isPresent();
        Optional<Integer> previous = runs.previousCleanFetched(sourceId, run.startedAt());
        if (previous.isPresent() && previous.get() >= config.zeroJobsMinPrevious()) {
            breach(run, AlertRule.ZERO_JOBS, "A clean run over %d target(s) fetched 0 postings; the previous clean run fetched %d."
                    .formatted(run.targets(), previous.get()));
        } else if (open) {
            breach(run, AlertRule.ZERO_JOBS,
                    "Still fetching 0 postings from %d target(s), with no errors.".formatted(run.targets()));
        }
    }

    private void errorRate(RunOutcome run) {
        if (run.targets() == 0) {
            return;
        }
        int errors = run.counts().errors();
        boolean overThreshold = BigDecimal.valueOf(errors)
                .compareTo(BigDecimal.valueOf(config.errorRateThreshold()).multiply(BigDecimal.valueOf(run.targets()))) > 0;
        boolean enoughTargets = run.targets() >= config.errorRateMinTargets() || errors == run.targets();
        if (overThreshold && enoughTargets) {
            breach(run, AlertRule.ERROR_RATE, "%d of %d target(s) failed (%d%%; the limit is %d%%)."
                    .formatted(errors, run.targets(), Math.round(100.0 * errors / run.targets()),
                            Math.round(100 * config.errorRateThreshold())));
        } else {
            resolve(run, AlertRule.ERROR_RATE);
        }
    }

    private void resolve(RunOutcome run, AlertRule rule) {
        if (store.resolve(run.source().id(), rule, run.finishedAt())) {
            log.info("Source {}: alert {} cleared by run {}", run.source().code(), rule, run.runId());
        }
    }

    private void breach(RunOutcome run, AlertRule rule, String detail) {
        UUID sourceId = run.source().id();
        Optional<SourceAlertStore.AlertRow> existing = store.find(sourceId, rule);
        boolean opened = existing.isEmpty() || !existing.get().active();
        if (opened) {
            store.open(sourceId, rule, run.runId(), detail, run.finishedAt());
        } else {
            store.touch(sourceId, rule, run.runId(), detail, run.finishedAt());
        }
        SourceAlertStore.AlertRow row = store.find(sourceId, rule).orElseThrow();
        boolean due = opened || row.lastNotifiedAt() == null || (config.reAlertInterval().isPositive()
                && !run.finishedAt().isBefore(row.lastNotifiedAt().plus(config.reAlertInterval())));
        if (due) {
            notifyAdmins(run, rule, detail, row, opened);
        } else {
            log.info("Source {}: alert {} still open (run {}), already announced", run.source().code(), rule,
                    run.runId());
        }
    }

    private void notifyAdmins(RunOutcome run, AlertRule rule, String detail, SourceAlertStore.AlertRow row,
            boolean opened) {
        String code = run.source().code();
        String delivery;
        if (recipients.isEmpty()) {
            log.warn("Source alert (no recipients configured, not emailed): {} {} - {}", code, rule, detail);
            store.markNotified(run.source().id(), rule, run.finishedAt());
            delivery = "log";
        } else {
            try {
                mailSender.send(compose(run, rule, detail, row, opened));
                store.markNotified(run.source().id(), rule, run.finishedAt());
                delivery = "email";
                log.info("Source {}: alert {} emailed", code, rule);
            } catch (RuntimeException e) {
                log.error("Source {}: could not email alert {}; it will be retried after the next run", code, rule, e);
                delivery = "failed";
            }
        }
        meters.counter("ingestion.alerts.fired", "source", code, "rule", rule.name(), "delivery", delivery)
                .increment();
    }

    /**
     * The email: the source, the rule, the numbers and a link to the admin page. It carries no error messages,
     * target identifiers or URLs from the run, only counts, so nothing a source returned or an adapter
     * reported can leak through it; the run history has the details behind the admin sign-in.
     */
    private SimpleMailMessage compose(RunOutcome run, AlertRule rule, String detail, SourceAlertStore.AlertRow row,
            boolean opened) {
        String code = run.source().code();
        String base = config.webBaseUrl().replaceAll("/+$", "");
        StringBuilder text = new StringBuilder();
        text.append("Source: ").append(code).append(" (").append(run.source().kind()).append(")\n");
        text.append("Rule: ").append(rule == AlertRule.ZERO_JOBS ? "no jobs returned" : "error rate above the limit")
                .append(" (").append(rule).append(")\n");
        text.append("What happened: ").append(detail).append('\n');
        text.append("Run: ").append(run.runId()).append(", ").append(run.status()).append(", fetched ")
                .append(run.counts().fetched()).append(", errors ").append(run.counts().errors()).append(" of ")
                .append(run.targets()).append(" target(s), finished ").append(run.finishedAt()).append('\n');
        if (!opened) {
            text.append("This has been going on since ").append(row.firstFiredAt()).append(" (")
                    .append(row.occurrences()).append(" runs in a row).\n");
        }
        text.append("\nSources and run history: ").append(base).append("/admin/ingestion\n\n");
        text.append("You get one email when a problem starts");
        if (config.reAlertInterval().isPositive()) {
            text.append(", and another every ").append(describe(config.reAlertInterval())).append(" while it lasts");
        }
        text.append(". It clears by itself when a run no longer breaches the rule.\n");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(config.from());
        message.setTo(recipients.toArray(String[]::new));
        message.setSubject("[JobFinder] Ingestion alert: " + code + " " + rule.phrase());
        message.setText(text.toString());
        return message;
    }

    private static String describe(java.time.Duration duration) {
        long hours = duration.toHours();
        if (hours >= 1 && duration.equals(java.time.Duration.ofHours(hours))) {
            return hours == 1 ? "hour" : hours + " hours";
        }
        long minutes = Math.max(1, duration.toMinutes());
        return minutes == 1 ? "minute" : minutes + " minutes";
    }
}
