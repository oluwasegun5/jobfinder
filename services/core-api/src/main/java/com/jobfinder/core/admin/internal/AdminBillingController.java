package com.jobfinder.core.admin.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jobfinder.core.billing.AiCostReports;
import com.jobfinder.core.billing.CostReport;
import com.jobfinder.core.billing.CostRow;
import com.jobfinder.core.billing.CreditAdjustments;
import com.jobfinder.core.identity.CurrentUser;
import com.jobfinder.core.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.Size;

/**
 * The AI cost dashboard (docs/adr/0025-ai-usage-ledger.md). Everything under {@code /admin} requires the ADMIN role
 * (see the security configuration); a signed-in non-admin gets 403. The figures are totals per day, feature and
 * model: no user is identified and no content is included. It also holds the manual credit adjustment an admin makes
 * after refunding a payment at the provider (docs/adr/0036-plans-credits-and-payments.md, refund addendum).
 */
@RestController
@RequestMapping("/admin/billing")
class AdminBillingController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminBillingController.class);

    private static final int DEFAULT_RANGE_DAYS = 7;

    /**
     * Totals for one group of calls. {@code day} ({@code yyyy-MM-dd}, UTC), {@code feature} and {@code model} are set
     * according to the grouping, the others are null. {@code failedCalls} are billed calls whose output was
     * discarded; they are included in {@code calls} and {@code costUsd}.
     */
    record CostRowResponse(LocalDate day, String feature, String model, long calls, long failedCalls,
            long inputTokens, long outputTokens, BigDecimal costUsd) {

        static CostRowResponse of(CostRow row) {
            return new CostRowResponse(row.day(), row.feature(), row.model(), row.calls(), row.failedCalls(),
                    row.inputTokens(), row.outputTokens(), row.costUsd());
        }
    }

    /**
     * AI cost from {@code from} to {@code to} (UTC days, both included). {@code byFeature} and {@code byModel} are most
     * expensive first, {@code byDay} oldest first, {@code byDayFeature} is cost per feature per day.
     */
    record CostReportResponse(LocalDate from, LocalDate to, CostRowResponse totals, List<CostRowResponse> byFeature,
            List<CostRowResponse> byDay, List<CostRowResponse> byModel, List<CostRowResponse> byDayFeature) {

        static CostReportResponse of(CostReport report) {
            return new CostReportResponse(report.from(), report.to(), CostRowResponse.of(report.totals()),
                    rows(report.byFeature()), rows(report.byDay()), rows(report.byModel()),
                    rows(report.byDayFeature()));
        }

        private static List<CostRowResponse> rows(List<CostRow> rows) {
            return rows.stream().map(CostRowResponse::of).toList();
        }
    }

    /**
     * Take credits back from one user. {@code delta} is negative; {@code idempotencyKey} is the admin's own (for
     * example the provider's refund id) and makes a repeat a no-op; {@code reason} is kept with the ledger line and
     * must not contain personal data.
     */
    record AdjustmentRequest(@NotBlank @Size(max = 100) String idempotencyKey,
            @NotNull @Negative @Digits(integer = 12, fraction = 6) BigDecimal delta,
            @NotBlank @Size(max = 500) String reason) {
    }

    /** {@code applied} is false when the same request had been applied before; {@code balance} is after the call. */
    record AdjustmentResponse(boolean applied, BigDecimal balance) {
    }

    private final AiCostReports reports;
    private final CreditAdjustments adjustments;
    private final Clock clock;

    AdminBillingController(AiCostReports reports, CreditAdjustments adjustments, Clock clock) {
        this.reports = reports;
        this.adjustments = adjustments;
        this.clock = clock;
    }

    /**
     * Writes one {@code REFUND_ADJUSTMENT} ledger line for the user. 201 when written, 200 when the same key, user and
     * delta were applied before, 404 {@code user_not_found}, 409 {@code idempotency_key_reused} when the key belongs to
     * another user or delta.
     */
    @PostMapping("/users/{userId}/adjustments")
    ResponseEntity<AdjustmentResponse> adjust(@PathVariable java.util.UUID userId,
            @Valid @RequestBody AdjustmentRequest request) {
        CreditAdjustments.Outcome outcome = adjustments.adjust(userId, request.delta(), request.idempotencyKey(),
                request.reason());
        return switch (outcome.result()) {
            case APPLIED -> {
                log.info("Admin credit adjustment: admin={} user={}", CurrentUser.require().id(), userId);
                yield ResponseEntity.status(HttpStatus.CREATED).body(new AdjustmentResponse(true, outcome.balance()));
            }
            case REPLAYED -> ResponseEntity.ok(new AdjustmentResponse(false, outcome.balance()));
            case USER_NOT_FOUND -> throw new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "No such user");
            case KEY_CONFLICT -> throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                    "That idempotency key was used for a different adjustment");
        };
    }

    /** Cost, tokens and calls by feature, day and model. Without a range, the last seven days including today. */
    @GetMapping("/costs")
    CostReportResponse costs(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1);
        try {
            return CostReportResponse.of(reports.report(start, end));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_range", e.getMessage());
        }
    }
}
