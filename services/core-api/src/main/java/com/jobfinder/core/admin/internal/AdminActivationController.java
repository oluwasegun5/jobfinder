package com.jobfinder.core.admin.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jobfinder.core.activation.ActivationFunnelReports;
import com.jobfinder.core.activation.FunnelReport;
import com.jobfinder.core.activation.FunnelStep;
import com.jobfinder.core.billing.TrialOutcomeReports;
import com.jobfinder.core.shared.ApiException;

/**
 * The activation funnel (docs/adr/0045-activation-funnel.md): how many of the users who signed up in a range reached
 * each step, and how the trial gate decided for them. Everything under {@code /admin} requires the ADMIN role (see the
 * security configuration). The figures are counts: no user is identified.
 */
@RestController
@RequestMapping("/admin/activation")
class AdminActivationController {

    private static final int DEFAULT_RANGE_DAYS = 30;

    /**
     * One funnel step. {@code count}: users who reached it and every step before it. {@code fromPrevious} and
     * {@code overall} (against signups) are conversion ratios from 0 to 1, absent when there is nothing to divide by.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StepResponse(FunnelStep step, long count, Double fromPrevious, Double overall) {
    }

    /** The counts of the users who signed up in the week starting {@code weekStart} (a Monday), in step order. */
    record CohortResponse(LocalDate weekStart, List<Long> counts) {
    }

    /** How the trial gate decided for the same users. Accounts from before the trial are not counted. */
    record TrialOutcomesResponse(long granted, long hold, long denied) {
    }

    /** The funnel for the signups from {@code from} to {@code to} (UTC days, both included). */
    record ActivationResponse(LocalDate from, LocalDate to, List<StepResponse> steps, List<CohortResponse> cohorts,
            TrialOutcomesResponse trial) {
    }

    private final ActivationFunnelReports funnel;
    private final TrialOutcomeReports trials;
    private final Clock clock;

    AdminActivationController(ActivationFunnelReports funnel, TrialOutcomeReports trials, Clock clock) {
        this.funnel = funnel;
        this.trials = trials;
        this.clock = clock;
    }

    /**
     * Per-step counts and conversion for the cohort that signed up in the range, per signup week, and the trial
     * outcomes. Without a range, the last thirty days including today. 400 {@code invalid_range} for an inverted or
     * too long range.
     */
    @GetMapping
    ActivationResponse activation(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1);
        FunnelReport report;
        try {
            report = funnel.report(start, end);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_range", e.getMessage());
        }
        TrialOutcomeReports.TrialOutcomes outcomes = trials.outcomes(start.atStartOfDay().toInstant(ZoneOffset.UTC),
                end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        return new ActivationResponse(report.from(), report.to(),
                report.steps().stream()
                        .map(s -> new StepResponse(s.step(), s.count(), s.fromPrevious(), s.overall())).toList(),
                report.cohorts().stream().map(c -> new CohortResponse(c.weekStart(), c.counts())).toList(),
                new TrialOutcomesResponse(outcomes.granted(), outcomes.hold(), outcomes.denied()));
    }
}
