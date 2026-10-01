package com.jobfinder.core.ingestion.internal;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A posting in the shape of the {@code jobs} table, produced by {@link JobNormalizer}. The enums are
 * the value sets the V17 migration constrains; the seniority set is the one profiles already use, so
 * matching can compare them directly.
 */
record NormalizedJob(String companyName, String normalizedCompany, String title, String normalizedTitle,
        String descriptionHtml, String descriptionText, String locationRaw, String city, String country,
        WorkMode workMode, EmploymentType employmentType, Seniority seniority, BigDecimal salaryMin,
        BigDecimal salaryMax, String salaryCurrency, SalaryPeriod salaryPeriod, String applyUrl, Instant postedAt,
        Instant expiresAt, String fingerprint) {

    enum WorkMode {
        REMOTE, HYBRID, ONSITE
    }

    enum EmploymentType {
        FULL_TIME, PART_TIME, CONTRACT, TEMPORARY, INTERNSHIP
    }

    enum Seniority {
        INTERN, JUNIOR, MID, SENIOR, LEAD, EXECUTIVE
    }

    enum SalaryPeriod {
        HOUR, DAY, WEEK, MONTH, YEAR
    }
}
