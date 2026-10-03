package com.jobfinder.core.jobs;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a company brief for interview prep may be made from: the job as ingestion stored it and the company record
 * (name, domain, size, industry) the job belongs to. Any company field may be null: ingestion only fills what the
 * source gave. Nothing else is held about the employer.
 */
public record JobForBrief(UUID id, String title, String company, String companyDomain, String companySize,
        String companyIndustry, String locationRaw, String city, String country, String workMode,
        String employmentType, String seniority, BigDecimal salaryMin, BigDecimal salaryMax, String salaryCurrency,
        String salaryPeriod, List<String> skills, String descriptionText) {
}
