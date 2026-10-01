package com.jobfinder.core.jobs;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a score is made from: the job's content as ingestion stored it. {@code postedAt} is when the job was posted,
 * or first seen when the source gave no date; {@code active} is false for expired jobs.
 */
public record JobForMatching(UUID id, String title, String company, String city, String country,
        String locationRaw, String workMode, String employmentType, String seniority, BigDecimal salaryMin,
        BigDecimal salaryMax, String salaryCurrency, String salaryPeriod, List<String> skills,
        String descriptionText, boolean active, Instant postedAt) {
}
