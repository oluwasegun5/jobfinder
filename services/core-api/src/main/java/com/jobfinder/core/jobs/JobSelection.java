package com.jobfinder.core.jobs;

import java.util.List;
import java.util.UUID;

/**
 * The stage-1 filters (docs/adr/0026-matching-engine.md). An empty list or a null value means "do not filter on
 * this", and a job that has no value for a field a filter looks at passes that filter: unknown is not a mismatch.
 *
 * @param userId             whose hidden jobs are left out
 * @param workModes          allowed work modes ({@code REMOTE}, {@code HYBRID}, {@code ONSITE})
 * @param locationTerms      lower-case place names; a job outside {@code REMOTE} passes if its city equals one, its
 *                           raw location contains one, or its country code is in {@code countryCodes}
 * @param countryCodes       upper-case ISO 3166 alpha-2 codes
 * @param minAnnualSalary    a floor on the top of a stated yearly salary, compared only with jobs that state a salary
 *                           with a pay period in {@code salaryCurrency} (other currencies are never converted)
 * @param salaryCurrency     the currency of the floor, upper case
 * @param seniorities        allowed seniority values
 * @param excludedCompanies  lower-case company names; compared with the company's name and its normalized name
 * @param excludedIndustries lower-case industries of excluded companies
 */
public record JobSelection(UUID userId, List<String> workModes, List<String> locationTerms,
        List<String> countryCodes, Integer minAnnualSalary, String salaryCurrency, List<String> seniorities,
        List<String> excludedCompanies, List<String> excludedIndustries) {
}
