package com.jobfinder.core.billing;

import java.time.LocalDate;
import java.util.List;

/**
 * AI cost between two UTC days (both inclusive). {@code totals} sums every call in the range; {@code byDay} is
 * oldest first, the others most expensive first. {@code byDayFeature} is cost per feature per day.
 */
public record CostReport(LocalDate from, LocalDate to, CostRow totals, List<CostRow> byFeature, List<CostRow> byDay,
        List<CostRow> byModel, List<CostRow> byDayFeature) {
}
