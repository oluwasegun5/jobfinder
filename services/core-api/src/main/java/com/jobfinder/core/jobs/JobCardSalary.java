package com.jobfinder.core.jobs;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The salary of a {@link JobCard} as the source stated it; {@code period} is HOUR, DAY, WEEK, MONTH or YEAR. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobCardSalary(BigDecimal min, BigDecimal max, String currency, String period) {
}
